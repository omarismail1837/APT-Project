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
    private volatile ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private static final long RECONNECT_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes
    private static final long[] BACKOFF_DELAYS_MS = {2000, 5000, 10000, 20000, 30000}; // so all clients dont reconnect at same instant

    private volatile boolean intentionalDisconnect = false;
    private volatile long disconnectedAt = -1;
    private volatile int reconnectAttempts = 0;
    private String lastUrl;

    // Set when a reconnect has occurred; cleared once switchToLiveMode fires onReconnected.
    private volatile boolean pendingReconnect = false;


    // Add a Runnable hook for UI feedback
    private Runnable onReconnecting;
    private Runnable onReconnected;

    private String userId;
    private String joinCode;

    public void setReconnectCredentials(String userId, String joinCode) {
        this.userId = userId;
        this.joinCode = joinCode;
    }

    public void setOnReconnecting(Runnable r) { this.onReconnecting = r; }
    public void setOnReconnected(Runnable r)  { this.onReconnected  = r; }

    public WebSocketService(String documentId, Consumer<Action> onActionReceived, Runnable onConnected) {
        this.docID = documentId;
        this.onActionReceived = onActionReceived;
        this.onConnected = onConnected;
    }

    // Ensure the scheduler exists
    private synchronized void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = Executors.newScheduledThreadPool(2);
        }
    }

    public void setOnDisconnected(Runnable onDisconnected) {
        this.onDisconnected = onDisconnected;
    }


    public boolean isConnected() {
        StompSession s = session;
        return s != null && s.isConnected();
    }

    public void disconnect() {
        intentionalDisconnect = true;
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
            if (scheduler != null) {
                scheduler.shutdownNow();
                // allow re-creation on next connect
                scheduler = null;
            }
        } catch (Exception ignored) {}

        if (onDisconnected != null) {
            try {
                onDisconnected.run();
            } catch (Exception ignored) {}
        }
    }

    public void connect(String url) {
        this.lastUrl = url;
        this.intentionalDisconnect = false;
        // Make sure the scheduler available
        ensureScheduler();
        String sockJsUrl = normalizeHttpUrl(url);
        connectWithSockJs(sockJsUrl);
    }

    private void connectWithSockJs(String url) {
        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        taskScheduler.initialize();
        stompClient.setTaskScheduler(taskScheduler);
        stompClient.setDefaultHeartbeat(new long[]{4000, 4000});

        stompClient.connectAsync(url, buildSessionHandler("sockjs"));
    }

    private StompSessionHandler buildSessionHandler(String transportName) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession s, StompHeaders headers) {
                session = s;
                boolean wasReconnect = reconnectAttempts > 0;
                reconnectAttempts = 0;
                disconnectedAt = -1;
                System.out.println("[WS] Connected via " + transportName);

                if (wasReconnect) {
                    // On reconnect: mark the flag so switchToLiveMode fires onReconnected
                    // after history has been replayed and pending local actions flushed.
                    // We do NOT call handleRemoteRestore() here — seenActionIds naturally
                    // deduplicates old history; only missed remote edits are applied.
                    pendingReconnect = true;
                }

                // subscribeToTopics handles both the initial-state replay and live
                // buffering for first-connect AND reconnect alike. Calling it once is
                // sufficient; do NOT call resubscribeInitialState() on top of this.
                subscribeToTopics();

                if (!wasReconnect && onConnected != null) {
                    onConnected.run();
                }
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                System.err.println("[WS] Transport error: " + ex.getMessage());
                session = null;
                if (!intentionalDisconnect) {
                    handleUnexpectedDisconnect();
                }
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
        // Send any local edits that were made while disconnected.
        flushPendingActions();
        // Only now — after remote edits are applied and local edits are sent —
        // notify the UI that the reconnect is complete.
        if (pendingReconnect) {
            pendingReconnect = false;
            if (onReconnected != null) onReconnected.run();
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
    private void handleUnexpectedDisconnect() {
        // ensure scheduler is available for scheduling reconnect attempts
        ensureScheduler();
        if (disconnectedAt < 0) {
            disconnectedAt = System.currentTimeMillis();
        }

        long elapsed = System.currentTimeMillis() - disconnectedAt;
        if (elapsed > RECONNECT_WINDOW_MS) {
            System.err.println("[WS] Reconnect window expired (5 min). Giving up.");
            session = null;
            if (onDisconnected != null) onDisconnected.run();
            return;
        }
        //pick delay
        long delayMs = BACKOFF_DELAYS_MS[Math.min(reconnectAttempts, BACKOFF_DELAYS_MS.length - 1)];
        reconnectAttempts++;

        System.out.printf("[WS] Scheduling reconnect attempt #%d in %ds%n",
                reconnectAttempts, delayMs / 1000);

        if (onReconnecting != null) onReconnecting.run();

        scheduler.schedule(() -> {
            if (intentionalDisconnect) return;
            if (System.currentTimeMillis() - disconnectedAt > RECONNECT_WINDOW_MS) {
                if (onDisconnected != null) onDisconnected.run();
                return;
            }
            System.out.println("[WS] Attempting reconnect #" + reconnectAttempts + "...");

            // Re-register with the server session before opening WS
            if (userId != null && joinCode != null) {
                try {
                    String urlStr = "https://apt-project-production-326d.up.railway.app/join"
                            + "?code=" + joinCode + "&userId=" + userId;
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL(urlStr).openConnection();
                    conn.setRequestMethod("GET");
                    conn.getResponseCode();
                    conn.disconnect();
                    System.out.println("[WS] Re-joined session for userId=" + userId);
                } catch (Exception e) {
                    System.err.println("[WS] Re-join failed: " + e.getMessage());
                }
            }

            connectWithSockJs(normalizeHttpUrl(lastUrl));
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}