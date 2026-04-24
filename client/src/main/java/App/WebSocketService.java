package App;

import App.crdt.action.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class WebSocketService {

    private volatile StompSession session;
    private final String documentId;
    private final Consumer<Action> onActionReceived;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Queue<Action> pendingActions = new ConcurrentLinkedQueue<>();
    private final Queue<Action> bufferedLiveUpdates = new ConcurrentLinkedQueue<>();
    private final AtomicInteger replayState = new AtomicInteger(0); // 0=none, 1=replaying, 2=ready
    private final Runnable onConnected;

    public WebSocketService(String documentId, Consumer<Action> onActionReceived, Runnable onConnected) {
        this.documentId = documentId;
        this.onActionReceived = onActionReceived;
        this.onConnected = onConnected;
    }

    public void connect(String url) {
        String nativeUrl = normalizeWebSocketUrl(url);
        String sockJsUrl = normalizeHttpUrl(url);
        boolean nativeFirst = Boolean.parseBoolean(System.getProperty("ws.nativeFirst", "false"));

        if (!nativeFirst) {
            connectWithSockJs(sockJsUrl);
            return;
        }

        AtomicBoolean fallbackStarted = new AtomicBoolean(false);
        WebSocketStompClient nativeClient = new WebSocketStompClient(new StandardWebSocketClient());
        nativeClient.setMessageConverter(new MappingJackson2MessageConverter());

        CompletableFuture<StompSession> future = nativeClient.connectAsync(nativeUrl, buildSessionHandler("native"));
        future.whenComplete((connectedSession, throwable) -> {
            if (throwable != null && fallbackStarted.compareAndSet(false, true)) {
                connectWithSockJs(sockJsUrl);
            }
        });
    }

    private void connectWithSockJs(String url) {
        SockJsClient sockJsClient = new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        stompClient.connectAsync(url, buildSessionHandler("sockjs"));
    }

    private StompSessionHandler buildSessionHandler(String transportName) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession s, StompHeaders headers) {
                session = s;
                System.out.println("[WS] Connected to Doc: " + documentId + " via " + transportName);
                subscribeToTopics();
                flushPendingActions();
                if (onConnected != null) onConnected.run();
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                System.err.println("[WS] Transport error: " + ex.getMessage());
            }
        };
    }

    private void subscribeToTopics() {
        if (session == null) return;

        // 1. Enter Replay Mode
        replayState.set(1);

        // 2. Subscribe to LIVE UPDATES (/topic)
        session.subscribe("/topic/docs/" + documentId + "/updates", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return Action.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                Action action = convertToAction(payload);
                if (action == null) return;

                // Buffer updates if we are still loading history
                if (replayState.get() == 1) {
                    bufferedLiveUpdates.offer(action);
                } else {
                    onActionReceived.accept(action);
                }
            }
        });

        // 3. Subscribe to INITIAL STATE (/app triggers @SubscribeMapping)
        session.subscribe("/app/docs/" + documentId + "/initial-state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return List.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                if (payload instanceof List<?> payloadList) {
                    System.out.println("[WS] Received history: " + payloadList.size() + " actions");
                    for (Object item : payloadList) {
                        Action action = convertToAction(item);
                        if (action != null) onActionReceived.accept(action);
                    }
                }

                // 4. History loaded. Flush the live buffer.
                replayState.set(2);
                drainBufferedLiveUpdates();
            }
        });
    }

    public void sendAction(Action action) {
        if (action == null) return;
        StompSession current = session;

        // DEBUG LOG
        String destination = "/app/docs/" + documentId + "/send-data";
        System.out.println("[WS DEBUG] Attempting to send to: " + destination);

        if (current != null && current.isConnected()) {
            current.send(destination, action);
        } else {
            System.out.println("[WS DEBUG] Send failed: Session is null or disconnected.");
            pendingActions.offer(action);
        }
    }

    private void drainBufferedLiveUpdates() {
        Action queued;
        while ((queued = bufferedLiveUpdates.poll()) != null) {
            onActionReceived.accept(queued);
        }
    }

    private void flushPendingActions() {
        StompSession current = session;
        if (current == null || !current.isConnected()) return;
        Action next;
        while ((next = pendingActions.poll()) != null) {
            current.send("/app/docs/" + documentId + "/send-data", next);
        }
    }

    private Action convertToAction(Object payload) {
        if (payload instanceof Action) return (Action) payload;
        if (payload instanceof Map) return objectMapper.convertValue(payload, Action.class);
        return null;
    }

    private String normalizeWebSocketUrl(String url) {
        if (url == null || url.isBlank()) return "ws://localhost:8080/ws-connect";
        return url.replace("https://", "wss://").replace("http://", "ws://");
    }

    private String normalizeHttpUrl(String url) {
        if (url == null || url.isBlank()) return "http://localhost:8080/ws-connect";
        return url.replace("wss://", "https://").replace("ws://", "http://");
    }
}