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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class WebSocketService {

    private volatile StompSession session;
    private final Consumer<Action> onActionReceived;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Queue<Action> pendingActions = new ConcurrentLinkedQueue<>();
    private final Queue<Action> bufferedLiveUpdates = new ConcurrentLinkedQueue<>();
    private final AtomicInteger replayState = new AtomicInteger(0);
    private final Runnable onConnected;
    // optional hook invoked when the client intentionally disconnects or is disconnected
    private Runnable onDisconnected;
    private final String docID;

    // Safety timer to prevent getting stuck in "Buffering" forever
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public WebSocketService(String documentId, Consumer<Action> onActionReceived, Runnable onConnected) {
        this.docID = documentId;
        this.onActionReceived = onActionReceived;
        this.onConnected = onConnected;
    }

    public void setOnDisconnected(Runnable onDisconnected) {
        this.onDisconnected = onDisconnected;
    }


    public boolean isConnected() {
        StompSession s = session;
        return s != null && s.isConnected();
    }

    public void disconnect() {
        StompSession s = session;
        if (s != null) {
            try {
                // Ask the STOMP session to disconnect gracefully
                s.disconnect();

            } catch (Exception ex) {
                // disconnect() may throw for already-closed sessions; nothing more we can do here.
                System.err.println("[WS] Error while disconnecting: " + ex.getMessage());
            }
        }

        // Null out the session reference so callers know we're disconnected
        session = null;

        // Stop the safety scheduler to avoid stray tasks running after disconnect.
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {}

        if (onDisconnected != null) {
            try {
                onDisconnected.run();
            } catch (Exception ignored) {}
        }
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
                System.out.println("[WS] Connected via " + transportName);
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
        replayState.set(1);

        // SAFETY VALVE: If history isn't received in 5s, force live mode anyway
        scheduler.schedule(() -> {
            if (replayState.get() == 1) {
                System.err.println("[WS] History timeout! Forcing Live Mode.");
                switchToLiveMode();
            }
        }, 5, TimeUnit.SECONDS);

        // 1. Live Broadcast
        session.subscribe("/topic/docs/" + docID + "/updates", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return Action.class; }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                Action action = convertToAction(payload);
                if (action == null) return;

                if (replayState.get() == 1) {
                    bufferedLiveUpdates.offer(action);
                } else {
                    onActionReceived.accept(action);
                }
            }
        });

        // 2. Initial State (History)
        session.subscribe("/app/docs/" + docID + "/initial-state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                // We accept Object.class because we will handle the byte[] or List manually
                return Object.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                System.out.println("[WS] Received history data of type: " + payload.getClass().getSimpleName());

                try {
                    List<Action> actions = null;

                    // HANDLE BYTE ARRAY (The "class [B" you are seeing)
                    if (payload instanceof byte[] bytes) {
                        System.out.println("[WS] Decoding raw bytes to List<Action>...");
                        actions = objectMapper.readValue(bytes,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, Action.class));
                    }
                    // HANDLE ALREADY CONVERTED LIST
                    else if (payload instanceof List<?> payloadList) {
                        actions = payloadList.stream()
                                .map(item -> convertToAction(item))
                                .toList();
                    }

                    if (actions != null) {
                        System.out.println("[WS] Applying " + actions.size() + " actions from history.");
                        for (Action a : actions) {
                            onActionReceived.accept(a);
                        }
                    }

                } catch (Exception e) {
                    System.err.println("[WS] Failed to decode history bytes: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    switchToLiveMode();
                }
            }
        });
    }

    private void switchToLiveMode() {
        if (replayState.get() == 2) return;
        System.out.println("[WS] Ready. Draining " + bufferedLiveUpdates.size() + " buffered actions.");
        replayState.set(2);
        Action queued;
        while ((queued = bufferedLiveUpdates.poll()) != null) {
            onActionReceived.accept(queued);
        }
    }

    public void sendAction(Action action) {
        if (action == null) return;
        action.setDocumentId(this.docID);
        StompSession current = session;
        if (current != null && current.isConnected()) {
            current.send("/app/docs/" + docID + "/send-data", action);
        } else {
            pendingActions.offer(action);
        }
    }

    public void resubscribeInitialState(String docId) {
        if (session == null || !session.isConnected()) return;
        replayState.set(1);
        bufferedLiveUpdates.clear();
        session.subscribe("/app/docs/" + docId + "/initial-state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return Object.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    List<Action> actions = null;
                    if (payload instanceof byte[] bytes) {
                        actions = objectMapper.readValue(bytes,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, Action.class));
                    } else if (payload instanceof List<?> list) {
                        actions = list.stream().map(item -> convertToAction(item)).toList();
                    }
                    if (actions != null) {
                        for (Action a : actions) { onActionReceived.accept(a); }
                    }
                } catch (Exception e) {
                    System.err.println("resubscribeInitialState decode failed: " + e.getMessage());
                } finally {
                    switchToLiveMode();
                }
            }
        });
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