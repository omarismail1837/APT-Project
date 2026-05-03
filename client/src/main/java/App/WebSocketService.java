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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class WebSocketService {

    private volatile StompSession session;
    private final Consumer<Action> onActionReceived;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Deque<Action> pendingActions = new ConcurrentLinkedDeque<>();
    private final BlockingQueue<Action> bufferedLiveUpdates = new LinkedBlockingQueue<>(5000);
    private final AtomicInteger replayState = new AtomicInteger(0);
    private final Runnable onConnected;
    private final ConcurrentHashMap<String, Action> localActionLog = new ConcurrentHashMap<>();
    // optional hook 3shan ama el client intentionally ye disconnects aw yb2a disconnected
    private Runnable onDisconnected;
    private final String docID;

    private Runnable onBeforeReconnectReplay;

    public void setOnBeforeReconnectReplay(Runnable r) { this.onBeforeReconnectReplay = r; }

    // persist pending actions so offline edits survive app restarts
    private final java.nio.file.Path pendingStorageDir =
            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "apt_pending_actions");
    private final java.nio.file.Path pendingStorageFile;

    // safety timer to prevent getting stuck in buffering forever
    private volatile ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private static final long RECONNECT_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes
    private static final long[] BACKOFF_DELAYS_MS = {2000, 5000, 10000, 20000, 30000}; // so all clients dont reconnect at same instant

    private volatile boolean intentionalDisconnect = false;
    private volatile boolean reconnecting = false; // true from transport drop until live mode restored
    private volatile long disconnectedAt = -1;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private String lastUrl;


    private volatile boolean pendingReconnect = false;
    private volatile boolean historyApplied = false;

    // runnable hook for UI feedback
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
        this.pendingStorageFile = pendingStorageDir.resolve(documentId + "-pending.json");

        try {
            loadPendingActionsFromDisk();
        } catch (Exception e) {
            System.err.println("[WS] Failed to load persisted pending actions: " + e.getMessage());
        }
    }

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
                // ask the STOMP session to disconnect
                s.disconnect();

            } catch (Exception ex) {
                System.err.println("[WS] Error while disconnecting: " + ex.getMessage());
            }
        }

        session = null;

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

        ensureScheduler();
        String sockJsUrl = normalizeHttpUrl(url);
        connectWithSockJs(sockJsUrl);
    }

    private void connectWithSockJs(String url) {
        // create the standard client
        StandardWebSocketClient rawClient = new StandardWebSocketClient();

        // config the internal container buffer (The "Low Level" Buffer)
        // this prevents "Max message size exceeded" errors
        rawClient.setUserProperties(Map.of(
                "org.apache.tomcat.websocket.binaryBufferSize", 10 * 1024 * 1024, // 10MB
                "org.apache.tomcat.websocket.textBufferSize", 10 * 1024 * 1024   // 10MB
        ));

        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(rawClient)));

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        // config STOMP level buffer (The "Message" Buffer)
        // thid controls the maximum size of a single STOMP frame
        stompClient.setInboundMessageSizeLimit(10 * 1024 * 1024); // 10MB

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
                boolean wasReconnect = reconnectAttempts.getAndSet(0) > 0;
                disconnectedAt = -1;
                System.out.println("[WS] Connected via " + transportName);

                if (wasReconnect) {
                    pendingReconnect = true;
                }

                // subscribeToTopics handles both the initial-state replay and live
                // buffering for first connect and reconnect. Calling it once is sufficient
                subscribeToTopics();

                if (!wasReconnect && onConnected != null) {
                    onConnected.run();
                }
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                if (session != null && s != session) {
                    System.out.println("[WS] Ignoring stale transport error from old session");
                    return;
                }
                System.err.println("[WS] Transport error: " + ex.getMessage());
                reconnecting = true;
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
        historyApplied = false;
        bufferedLiveUpdates.clear();

        scheduler.schedule(() -> {
            if (replayState.get() == 1) {
                System.err.println("[WS] History timeout! Forcing Live Mode.");
                historyApplied = true;
                switchToLiveMode();
            }
        }, 5, TimeUnit.SECONDS);

        //live updates
        session.subscribe("/topic/docs/" + docID + "/updates", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return Action.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                Action action = convertToAction(payload);
                if (action == null) return;
                if (replayState.get() == 1) {
                    // use .offer() to check if full
                    boolean accepted = bufferedLiveUpdates.offer(action);
                    if (!accepted) {
                        System.err.println("[WS] Buffer full! Dropping update or forcing Live mode.");
                        switchToLiveMode();
                    }
                } else {
                    onActionReceived.accept(action);
                }
            }
        });

        // history
        session.subscribe("/app/docs/" + docID + "/initial-state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return Object.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                System.out.println("[WS] Received history data of type: "
                        + payload.getClass().getSimpleName());
                try {
                    List<Action> actions = null;

                    if (payload instanceof byte[] bytes) {
                        System.out.println("[WS] Decoding raw bytes to List<Action>...");
                        actions = objectMapper.readValue(bytes,
                                objectMapper.getTypeFactory()
                                        .constructCollectionType(List.class, Action.class));
                    } else if (payload instanceof List<?> payloadList) {
                        actions = payloadList.stream()
                                .map(item -> convertToAction(item))
                                .toList();
                    }

                    if (actions != null) {
                        System.out.println("[WS] Applying " + actions.size() + " actions from history.");

                        if (pendingReconnect && onBeforeReconnectReplay != null) {
                            onBeforeReconnectReplay.run();
                        }

                        for (Action a : actions) {
                            onActionReceived.accept(a);
                        }

                        //find actions
                        if (!localActionLog.isEmpty()) {
                            Set<String> confirmed = new HashSet<>();
                            for (Action a : actions) {
                                confirmed.add(a.getSiteID() + "-" + a.getClock());
                            }

                            // exclude anything already sitting in pendingActions
                            Set<String> alreadyQueued = new HashSet<>();
                            for (Action a : pendingActions) {
                                alreadyQueued.add(a.getSiteID() + "-" + a.getClock());
                            }

                            List<Action> missing = localActionLog.entrySet().stream()
                                    .filter(e -> !confirmed.contains(e.getKey()))
                                    .filter(e -> !alreadyQueued.contains(e.getKey()))
                                    .map(Map.Entry::getValue)
                                    .sorted(Comparator.comparingLong(Action::getClock))
                                    .collect(java.util.stream.Collectors.toList());

                            if (!missing.isEmpty()) {
                                System.out.println("[WS] Reconciliation: " + missing.size()
                                        + " send-but-lost actions re-queued.");
                                for (int i = missing.size() - 1; i >= 0; i--) {
                                    pendingActions.addFirst(missing.get(i));
                                }
                            } else {
                                System.out.println("[WS] Reconciliation: no lost actions.");
                            }
                            localActionLog.clear();
                        }
                    }

                } catch (Exception e) {
                    System.err.println("[WS] Failed to decode history: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    historyApplied = true;
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
        if (historyApplied) {
            // send any local edits made when disconnected
            flushPendingActions();
        }

        // notify the ui reconnect is complete and re-broadcast presence
        reconnecting = false; // live mode restored
        if (pendingReconnect) {
            pendingReconnect = false;

            if (onConnected != null) onConnected.run();
            if (onReconnected != null) onReconnected.run();
        }
    }

    public void sendAction(Action action) {
        if (action == null) return;
        action.setDocumentId(this.docID);

        String type = action.getActionType();
        if (!type.equals("CURSOR") && !type.equals("CURSOR_REMOVE")
                && !type.equals("PRESENCE") && !type.equals("DISCONNECT")) {
            String key = action.getSiteID() + "-" + action.getClock();
            localActionLog.put(key, action);
        }

        StompSession current = session;
        if (!reconnecting && current != null && current.isConnected()) {
            try {
                current.send("/app/docs/" + docID + "/send-data", action);
            } catch (Exception e) {
                System.err.println("[WS] sendAction failed, queuing: " + e.getMessage());
                pendingActions.offer(action);
                try {
                    persistPendingActionsToDisk();
                } catch (Exception ex) {
                    System.err.println("[WS] Failed to persist pending actions: " + ex.getMessage());
                }
                ensureScheduler();
                scheduler.schedule(this::flushPendingActions, 2, TimeUnit.SECONDS);
            }
        } else {
            pendingActions.offer(action);
            try {
                persistPendingActionsToDisk();
            } catch (Exception e) {
                System.err.println("[WS] Failed to persist pending actions: " + e.getMessage());
            }
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
        System.out.println("[WS] Flushing " + pendingActions.size() + " pending actions");
        Action next;
        boolean drained = true;
        while ((next = pendingActions.poll()) != null) {
            System.out.println("[WS] Flushing " + next.getActionType() + " clock=" + next.getClock() + " parent=" + next.getStartCharID());
            try {
                current.send("/app/docs/" + docID + "/send-data", next);
            } catch (Exception e) {
                System.err.println("[WS] flushPendingActions send failed, re-queuing: " + e.getMessage());
                pendingActions.offer(next);
                drained = false;
                // schedule a retry so the remaining queue isn't silently abandoned.
                ensureScheduler();
                scheduler.schedule(this::flushPendingActions, 2, TimeUnit.SECONDS);
                break;
            }
        }
        if (drained && pendingActions.isEmpty()) {
            try {
                clearPendingActionsFile();
            } catch (Exception e) {
                System.err.println("[WS] Failed to clear persisted pending actions: " + e.getMessage());
            }
        }
    }

    private synchronized void persistPendingActionsToDisk() throws java.io.IOException {
        try {
            if (!java.nio.file.Files.exists(pendingStorageDir)) {
                java.nio.file.Files.createDirectories(pendingStorageDir);
            }
            List<Action> snapshot = new ArrayList<>(pendingActions);
            objectMapper.writeValue(pendingStorageFile.toFile(), snapshot);
        } catch (Exception e) {
            throw new java.io.IOException(e);
        }
    }

    private synchronized void loadPendingActionsFromDisk() throws java.io.IOException {
        try {
            if (pendingStorageFile != null && java.nio.file.Files.exists(pendingStorageFile)) {
                List<Action> persisted = objectMapper.readValue(pendingStorageFile.toFile(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Action.class));
                if (persisted != null) {
                    for (Action a : persisted) pendingActions.offer(a);
                }
            }
        } catch (Exception e) {
            throw new java.io.IOException(e);
        }
    }

    private synchronized void clearPendingActionsFile() throws java.io.IOException {
        try {
            if (pendingStorageFile != null && java.nio.file.Files.exists(pendingStorageFile)) {
                java.nio.file.Files.delete(pendingStorageFile);
            }
        } catch (Exception e) {
            throw new java.io.IOException(e);
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
        int attemptNumber = reconnectAttempts.incrementAndGet();
        long delayMs = BACKOFF_DELAYS_MS[Math.min(attemptNumber - 1, BACKOFF_DELAYS_MS.length - 1)];

        System.out.printf("[WS] Scheduling reconnect attempt #%d in %ds%n",
                attemptNumber, delayMs / 1000);

        if (onReconnecting != null) onReconnecting.run();

        scheduler.schedule(() -> {
            if (intentionalDisconnect) return;
            if (System.currentTimeMillis() - disconnectedAt > RECONNECT_WINDOW_MS) {
                if (onDisconnected != null) onDisconnected.run();
                return;
            }
            System.out.println("[WS] Attempting reconnect #" + attemptNumber + "...");

            // ree-register with the server session abl opening the stomp connection
            // sends bufffered local edits otherwise canEdit=false w they are silently droppedd
            if (userId != null && joinCode != null) {
                try {
                    String urlStr = "https://apt-project-production-326d.up.railway.app/join"
                            + "?code=" + joinCode + "&userId=" + userId;
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL(urlStr).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    int status = conn.getResponseCode();
                    String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    conn.disconnect();
                    System.out.println("[WS] Re-joined session for userId=" + userId + " (HTTP " + status + ") response=" + body);
                } catch (Exception e) {
                    System.err.println("[WS] Re-join failed: " + e.getMessage());
                }
            }

            connectWithSockJs(normalizeHttpUrl(lastUrl));
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
