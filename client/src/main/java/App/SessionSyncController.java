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
            if (host.connectedLabel != null) host.connectedLabel.setText("Disconnected");
        }));

        wsService.setOnReconnecting(() -> javafx.application.Platform.runLater(() -> {
            if (host.connectedLabel != null) host.connectedLabel.setText("Reconnecting...");
        }));

        wsService.setOnReconnected(() -> javafx.application.Platform.runLater(() -> {
            // History has already been replayed and pending local edits flushed by
            // WebSocketService before this callback fires. Just refresh the UI label.
            if (host.connectedLabel != null) host.connectedLabel.setText("Connected");
        }));
        host.setWsService(wsService);
        wsService.connect(BlankController.WS_URL);
        String joinCode = host.canEdit()
                ? host.getEditCode()
                : host.getViewCode();
        wsService.setReconnectCredentials(String.valueOf(host.getMySiteID()), joinCode);
    }

    void handleRemoteAction(Action action) {
        if (action == null) return;
        System.out.println("CLIENT RECEIVED: " + action.getActionType() + " from " + action.getSiteID());
        if (!host.getDocID().equals(action.getDocumentId())) return;

        String type = action.getActionType();

        switch(type) {
            case "PRESENCE":
                presenceController.handleRemotePresence(action);
                break;

            case "CURSOR":
                presenceController.handleRemoteCursor(action);
                break;

            case "DISCONNECT":
                presenceController.handleRemoteDisconnect(action);
                break;

            case "CURSOR_REMOVE":
                presenceController.handleCursorRemove(action);
                break;

            case "RESTORE":
                host.handleRemoteRestore();
                break;

            case "INSERT":
            case "DELETE":
            case "UPDATE":
            case "BOLD":
            case "ITALIC":
            case "HIGHLIGHT":
            case "UNDELETE":
                String actionId = host.buildActionId(action);
                if (host.getSeenActionIds().contains(actionId)) return;

                host.getSeenActionIds().add(actionId);
                host.observeClock(action);
                host.getBlockDLL().applyAction(action);
                int caretSnapshot = host.textArea.getCaretPosition();
                int anchorSnapshot = host.textArea.getAnchor();
                documentController.rerender(caretSnapshot, anchorSnapshot);
                break;

            case "RENAME":
                host.handleRemoteRename(action.getExtraData());
                break;
        }
    }

    void close() {
        try {
            if (host.getWsService() != null) {
                long now = host.now();
                String username = host.getUsername();
                Action cursorRemove = new Action(host.nextClock(), now, host.getMySiteID(), host.getDocID(),
                        "CURSOR_REMOVE", null, null, username);
                Action disconnect = new Action(host.nextClock(), now + 1, host.getMySiteID(), host.getDocID(),
                        "DISCONNECT", null, null, username);

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