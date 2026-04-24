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
    private final Consumer<Action> onActionReceived;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Queue<Action> pendingActions = new ConcurrentLinkedQueue<>();
    private final Queue<Action> bufferedLiveUpdates = new ConcurrentLinkedQueue<>();

    // 0 = Disconnected, 1 = Loading History, 2 = Ready (Live)
    private final AtomicInteger replayState = new AtomicInteger(0);
    private final Runnable onConnected;
    private final String docID;

    public WebSocketService(String documentId, Consumer<Action> onActionReceived, Runnable onConnected) {
        this.docID = documentId;
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
        System.out.println("[WS] Connecting to " + nativeUrl + " (native)");

        WebSocketStompClient nativeClient = new WebSocketStompClient(new StandardWebSocketClient());
        nativeClient.setMessageConverter(new MappingJackson2MessageConverter());

        CompletableFuture<StompSession> future = nativeClient.connectAsync(nativeUrl, buildSessionHandler("native"));
        future.whenComplete((connectedSession, throwable) -> {
            if (throwable != null && fallbackStarted.compareAndSet(false, true)) {
                System.err.println("[WS] Native connect failed, falling back: " + throwable.getMessage());
                connectWithSockJs(sockJsUrl);
            }
        });
    }

    private void connectWithSockJs(String url) {
        System.out.println("[WS] Connecting to " + url + " (sockjs)");
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
                System.out.println("[WS] Connected via " + transportName);
                subscribeToTopics();
                flushPendingActions();
                if (onConnected != null) onConnected.run();
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                System.err.println("[WS] " + transportName + " transport error: " + ex.getMessage());
            }
        };
    }

    private void subscribeToTopics() {
        if (session == null) return;
        replayState.set(1);

        // 1. Live Broadcast Channel
        session.subscribe("/topic/docs/" + docID + "/updates", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return Action.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                Action action = convertToAction(payload);
                if (action == null) return;

                if (replayState.get() == 1) {
                    System.out.println("[WS] Buffering live update...");
                    bufferedLiveUpdates.offer(action);
                } else {
                    onActionReceived.accept(action);
                }
            }
        });

        // 2. Initial State Channel (History)
        session.subscribe("/app/docs/" + docID + "/initial-state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return List.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                System.out.println("[WS] Received history. Processing...");
                try {
                    if (payload instanceof List<?> payloadList) {
                        for (Object item : payloadList) {
                            Action action = convertToAction(item);
                            if (action != null) onActionReceived.accept(action);
                        }
                    }
                } finally {
                    // Always transition to live mode, even if list was empty
                    switchToLiveMode();
                }
            }
        });
    }

    private void switchToLiveMode() {
        System.out.println("[WS] Switching to LIVE. Draining buffer: " + bufferedLiveUpdates.size());
        replayState.set(2);
        Action queued;
        while ((queued = bufferedLiveUpdates.poll()) != null) {
            onActionReceived.accept(queued);
        }
    }

    public void sendAction(Action action) {
        if (action == null) return;

        action.setDocumentId(this.docID); // Ensure ID is present

        StompSession current = session;
        if (current != null && current.isConnected()) {
            current.send("/app/docs/" + docID + "/send-data", action);
        } else {
            pendingActions.offer(action);
            System.out.println("[WS SEND] Queued. Pending: " + pendingActions.size());
        }
    }

    private void flushPendingActions() {
        StompSession current = session;
        if (current == null || !current.isConnected()) return;

        Action next;
        while ((next = pendingActions.poll()) != null) {
            current.send("/app/docs/" + docID + "/send-data", next);
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