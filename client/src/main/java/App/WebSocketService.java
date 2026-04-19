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

// comm layer between client & server
public class WebSocketService {

    // the active websocket connection
    // volatile = multiple threads can safely read it
    // StompSession is the object used to send messages
    private volatile StompSession session;
    private final Consumer<Action> onActionReceived;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // actions sent before the connection is established go here
    private final Queue<Action> pendingActions = new ConcurrentLinkedQueue<>();

    // replay past edits first and THEN live actions
    private final Queue<Action> bufferedLiveUpdates = new ConcurrentLinkedQueue<>();
    private final AtomicInteger replayState = new AtomicInteger(0); // 0=none, 1=replaying, 2=ready

    public WebSocketService(Consumer<Action> onActionReceived) {
        this.onActionReceived = onActionReceived;
    }

    public void connect(String url) {
        String nativeUrl = normalizeWebSocketUrl(url);
        String sockJsUrl = normalizeHttpUrl(url);
        // a sys property flag to choose connection strategy. by def it uses SockJS
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
            if (throwable == null) {
                return;
            }

            System.err.println("[WS] Native connect failed: " + throwable.getMessage());
            if (fallbackStarted.compareAndSet(false, true)) {
                connectWithSockJs(sockJsUrl);
            }
        });
    }

    private void connectWithSockJs(String url) {
        System.out.println("[WS] Connecting to " + url + " (sockjs fallback)");

        SockJsClient sockJsClient = new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        CompletableFuture<StompSession> future = stompClient.connectAsync(url, buildSessionHandler("sockjs"));
        future.whenComplete((connectedSession, throwable) -> {
            if (throwable != null) {
                System.err.println("[WS] SockJS connect failed: " + throwable.getMessage());
            }
        });
    }

    private StompSessionHandler buildSessionHandler(String transportName) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession s, StompHeaders headers) {
                session = s;
                System.out.println("[WS] Connected via " + transportName + ". Session=" + s.getSessionId());
                subscribeToTopics();
                flushPendingActions();
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                System.err.println("[WS] " + transportName + " transport error: " + ex.getMessage());
            }
        };
    }

    private void subscribeToTopics() {
        if (session == null) {
            return;
        }

        replayState.set(1);

        // live updates channel
        session.subscribe("/topic/updates", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Action.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                Action action = convertToAction(payload);
                if (action == null) {
                    return;
                }

                if (replayState.get() == 1) {
                    bufferedLiveUpdates.offer(action);
                    return;
                }

                onActionReceived.accept(action);
            }
        });

        // request doc history from server
        session.subscribe("/app/initial-state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return List.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                if (!(payload instanceof List<?> payloadList)) {
                    replayState.set(2);
                    drainBufferedLiveUpdates();
                    return;
                }

                for (Object item : payloadList) {
                    Action action = convertToAction(item);
                    if (action != null) {
                        onActionReceived.accept(action);
                    }
                }

                replayState.set(2);
                drainBufferedLiveUpdates();
            }
        });
    }

    private Action convertToAction(Object payload) {
        if (payload instanceof Action) {
            return (Action) payload;
        }
        if (payload instanceof Map<?, ?>) {
            return objectMapper.convertValue(payload, Action.class);
        }
        return null;
    }

    private void drainBufferedLiveUpdates() {
        Action queued;
        while ((queued = bufferedLiveUpdates.poll()) != null) {
            onActionReceived.accept(queued);
        }
    }

    // if connected, send immediately
    // if not, queue it for later
    public void sendAction(Action action) {
        if (action == null) {
            return;
        }

        StompSession current = session;
        if (current != null && current.isConnected()) {
            current.send("/app/send-data", action);
            return;
        }

        pendingActions.offer(action);
        System.out.println("[WS SEND] Session not ready. Queued action. Pending=" + pendingActions.size());
    }

    private void flushPendingActions() {
        StompSession current = session;
        if (current == null || !current.isConnected()) {
            return;
        }

        int flushed = 0;
        Action next;
        while ((next = pendingActions.poll()) != null) {
            current.send("/app/send-data", next);
            flushed++;
        }

        if (flushed > 0) {
            System.out.println("[WS SEND] Flushed queued actions=" + flushed);
        }
    }

    private String normalizeWebSocketUrl(String url) {
        if (url == null || url.isBlank()) {
            return "ws://localhost:8080/ws-connect";
        }
        if (url.startsWith("https://")) {
            return "wss://" + url.substring("https://".length());
        }
        if (url.startsWith("http://")) {
            return "ws://" + url.substring("http://".length());
        }
        return url;
    }

    private String normalizeHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8080/ws-connect";
        }
        if (url.startsWith("wss://")) {
            return "https://" + url.substring("wss://".length());
        }
        if (url.startsWith("ws://")) {
            return "http://" + url.substring("ws://".length());
        }
        return url;
    }
}