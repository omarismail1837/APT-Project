package App;

import App.crdt.action.Action;

class SessionSyncController {

    private final BlankController host;
    private final EditorDocumentController documentController;
    private final PresenceController presenceController;

    SessionSyncController(BlankController host,
                          EditorDocumentController documentController,
                          PresenceController presenceController) {
        this.host = host;
        this.documentController = documentController;
        this.presenceController = presenceController;
    }

    void setupWebSocket() {
        Runnable onConnected;
        if (host.canEdit()) {
            onConnected = () -> javafx.application.Platform.runLater(() ->
                    {
                        presenceController.broadcastPresence();
                        presenceController.broadcastCursorPosition(host.textArea.getCaretPosition(), true);
                    }
            );
        } else {
            onConnected = () -> javafx.application.Platform.runLater(
                    presenceController::broadcastPresence
            );
        }
        WebSocketService wsService = new WebSocketService(
                host.getDocID(),
                action -> javafx.application.Platform.runLater(() -> handleRemoteAction(action)),
                onConnected
        );

        wsService.setOnDisconnected(() -> javafx.application.Platform.runLater(() -> {
            if (host.connectedLabel != null) {
                host.connectedLabel.setText("Disconnected");
            }
        }));

        host.setWsService(wsService);
        wsService.connect(BlankController.WS_URL);
    }

    void handleRemoteAction(Action action) {
        if (action == null) return;
        if (!host.getDocID().equals(action.getDocumentId())) return;

        String type = action.getActionType();

        if ("PRESENCE".equals(type)){
            presenceController.handleRemotePresence(action);
            return;
        }
        if ("CURSOR".equals(type)) {
            presenceController.handleRemoteCursor(action);
            return;
        }
        if ("DISCONNECT".equals(type)) {
            presenceController.handleRemoteDisconnect(action);
            return;
        }
        if ("CURSOR_REMOVE".equals(type)) {
            presenceController.handleCursorRemove(action);
            return;
        }

        if ("INSERT".equals(type)
                || "DELETE".equals(type)
                || "BOLD".equals(type)
                || "ITALIC".equals(type)) {

            String actionId = host.buildActionId(action);
            if (host.getSeenActionIds().contains(actionId)) return;

            host.getSeenActionIds().add(actionId);
            host.getBlockDLL().applyAction(action);
            int caretSnapshot = host.textArea.getCaretPosition();
            int anchorSnapshot = host.textArea.getAnchor();
            documentController.rerender(caretSnapshot, anchorSnapshot);
        }
    }

    void close() {
        try {
            if (host.getWsService() != null) {
                long now = host.now();
                String userName = presenceController.currentUserName();
                Action cursorRemove = new Action(host.nextClock(), now, host.getMySiteID(), host.getDocID(),
                        "CURSOR_REMOVE", null, null, userName);
                Action disconnect = new Action(host.nextClock(), now + 1, host.getMySiteID(), host.getDocID(),
                        "DISCONNECT", null, null, userName);

                try {
                    host.getWsService().sendAction(cursorRemove);
                    host.getWsService().sendAction(disconnect);
                } catch (Exception e) {
                    System.err.println("Failed to send disconnect cleanup actions: " + e.getMessage());
                }

                new Thread(() -> {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        host.getWsService().disconnect();
                    } catch (Exception ex) {
                        System.err.println("Error while disconnecting WS: " + ex.getMessage());
                    }
                }, "ws-disconnect-thread").start();
            }
            presenceController.removeAllCarets();
        } catch (Exception ex) {
            System.err.println("Error while closing BlankController: " + ex.getMessage());
        }
    }
}
