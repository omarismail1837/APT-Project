package App;

import App.crdt.action.Action;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Path;
import org.fxmisc.richtext.Caret;
import org.fxmisc.richtext.CaretNode;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.datafaker.Faker;

class PresenceController {

    private final BlankController host;
    private final EditorDocumentController documentController;
    private final Map<Integer, String> remoteCursorPositions = new LinkedHashMap<>();
    private final Map<Integer, String> remoteUserNames = new LinkedHashMap<>();
    private final Map<Integer, CaretNode> remoteCarets = new HashMap<>();
    private final Map<Integer, Integer> siteColorIndices = new HashMap<>();
    private final Map<Integer, Integer> currentlyTakenColors = new HashMap<>();
    private long lastCursorBroadcastMs = 0;
    private final String username;
    private final String displayName;


    PresenceController(BlankController host, EditorDocumentController documentController) {
        this.host = host;
        this.documentController = documentController;
        username = host.getUsername();
        displayName = username != null ? username + " (You)" : "Anonymous " + new Faker().animal().name() + " (You)";
    }

    void setupCaretListener() {
        host.textArea.caretPositionProperty().addListener((obs, oldVal, newVal) -> {
            updateLineCol();
            if (!host.isRemoteUpdate()) {
                broadcastCursorPosition(newVal.intValue(), false);
            }
        });
    }

    void setUpLocalCaretColor() {
        if (host.textArea == null) return;

        host.textArea.setStyle("-fx-caret-color: white;");

        javafx.application.Platform.runLater(() -> {
            Node localCaret = host.textArea.lookup(".caret"); // .caret only finds in the built-in caret
            if (localCaret instanceof Path path) {
                path.setStroke(Color.web("white"));
            }
        });
    }

    void handleRemoteCursor(Action action) {
        int siteID = action.getSiteID();
        if (siteID == host.getMySiteID()) return;
        assignColor(siteID);
        remoteCursorPositions.put(siteID, action.getStartCharID());

        host.withRemoteFlag(documentController::refreshUI);
    }

    void handleRemoteDisconnect(Action action) {
        int siteID = action.getSiteID();
        if (siteID == host.getMySiteID()) return;

        remoteCursorPositions.remove(siteID);
        remoteUserNames.remove(siteID);
        releaseColor(siteID);
        removeRemoteCaret(siteID);
        host.withRemoteFlag(documentController::refreshUI);
    }

    void handleCursorRemove(Action action) {
        int siteID = action.getSiteID();
        releaseColor(siteID);
        remoteCursorPositions.remove(siteID);
        remoteUserNames.remove(siteID);
        removeRemoteCaret(siteID);
        host.withRemoteFlag(documentController::refreshUI);
    }

    void handleRemotePresence(Action action) {
        int siteID = action.getSiteID();
        if (siteID == host.getMySiteID()) return;
        String extra = action.getExtraData();
        if (extra != null && !extra.isBlank()) {
            remoteUserNames.put(siteID, extra);

        } else {
            remoteUserNames.putIfAbsent(siteID, "Anonymous " + new Faker().animal().name());
        }
        System.out.println("PRESENCE from " + siteID + " name=" + extra);
        host.withRemoteFlag(documentController::refreshUI);
        host.withRemoteFlag(this::updateActiveUsersPanel);
    }

    void broadcastCursorPosition(int caretPos, boolean force) {
        if (host.getWsService() == null) return;
        long now = host.now();
        if (!force && now - lastCursorBroadcastMs < BlankController.CURSOR_THROTTLE_MS) return;
        lastCursorBroadcastMs = now;

        String charID = documentController.resolveCharIDForCaret(caretPos);
        if (charID == null) return;

        Action action = new Action(host.nextClock(), now, host.getMySiteID(), host.getDocID(),
                "CURSOR", charID, null, username);
        host.getWsService().sendAction(action);
    }

    void broadcastPresence()
    {
        Action action = new Action(host.nextClock(), host.now(), host.getMySiteID(), host.getDocID(),
                        "PRESENCE", null, null, username);
        host.getWsService().sendAction(action);
    }

    void updateRemoteCarets() {
        if (host.textArea == null) return;

        for (Integer siteID : new ArrayList<>(remoteCarets.keySet())) {
            if (!remoteCursorPositions.containsKey(siteID)) {
                removeRemoteCaret(siteID);
            }
        }

        for (Map.Entry<Integer, String> entry : remoteCursorPositions.entrySet()) {
            int siteID = entry.getKey();
            if (siteID == host.getMySiteID()) continue;

            int position = documentController.resolveTextAreaIndexForCharID(entry.getValue());
            CaretNode caret = remoteCarets.computeIfAbsent(siteID, this::createRemoteCaret);
            int clamped = Math.max(0, Math.min(position, host.textArea.getLength()));
            TwoDimensional.Position areaPosition =
                    host.textArea.offsetToPosition(clamped, TwoDimensional.Bias.Forward);
            caret.moveTo(areaPosition.getMajor(), areaPosition.getMinor());
        }
    }

    void updateActiveUsersPanel() {
        if (host.activeUsersBox == null) return;
        host.activeUsersBox.getChildren().clear();
        String youColor;
        String youText = displayName;
        boolean canEdit = host.canEdit();
        if (canEdit) youColor = "white";
        else youColor = "gray";
        boolean viewer = !canEdit;

        host.activeUsersBox.getChildren().add(makeUserRow(youText, youColor, viewer));

        List<Integer> activeSites = new ArrayList<>(remoteUserNames.keySet());
        Collections.sort(activeSites);
        activeSites.forEach(siteID -> {

            String name = remoteUserNames.getOrDefault(siteID, displayName);
            String color = colorForSite(siteID);
            boolean isViewer = !remoteCursorPositions.containsKey(siteID);
            host.activeUsersBox.getChildren().add(makeUserRow(name, color, isViewer));
        });

        if (host.connectedLabel != null) {
            host.connectedLabel.setText((1 + activeSites.size())
                    + " users connected");
        }
    }

    void updateLineCol() {
        if (host.lineColLabel == null) return;
        int caretPos = host.textArea.getCaretPosition();
        String text = host.textArea.getText();
        int line = 1;
        int col = 1;
        for (int i = 0; i < caretPos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        host.lineColLabel.setText("Line " + line + ", Col " + col);
    }

    void removeAllCarets() {
        new ArrayList<>(remoteCarets.keySet()).forEach(this::removeRemoteCaret);
    }

    private CaretNode createRemoteCaret(int siteID) {
        CaretNode caret = new CaretNode("remote-caret-" + siteID, host.textArea);
        caret.setShowCaret(Caret.CaretVisibility.ON);
        caret.setStroke(Color.web(colorForSite(siteID)));
        caret.setStrokeWidth(2);
        caret.setManaged(false);
        caret.setMouseTransparent(true);
        caret.setFocusTraversable(false);
        host.textArea.addCaret(caret);
        return caret;
    }

    private void removeRemoteCaret(int siteID) {
        CaretNode caret = remoteCarets.remove(siteID);
        if (caret == null || host.textArea == null) return;

        host.textArea.removeCaret(caret);
        caret.dispose();
        // fix stale caret after disconnect?
        javafx.application.Platform.runLater(() -> {
            host.textArea.requestFocus();
        });
    }

    private String colorForSite(int siteID) {
        Integer colorIndex = siteColorIndices.get(siteID);

        if (colorIndex == null) {
            return "gray";
        }

        return BlankController.USER_COLORS[colorIndex];
    }

    private HBox makeUserRow(String name, String color, boolean isViewer) {
        Circle dot = new Circle(4);
        dot.setFill(Color.web(color));
        Label label = new Label(name);
        if (isViewer) {
            label.setStyle("-fx-text-fill: #808080; -fx-font-style: italic;");
        } else {
            label.setStyle("-fx-text-fill: #e0e0e0;");
        }
        HBox row = new HBox(10, dot, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }


    private int assignColor(int siteID)
    {
        Integer assigned = siteColorIndices.get(siteID);
        if (assigned != null) {
            if (isColorAvailForSite(assigned, siteID)) {
                currentlyTakenColors.put(assigned, siteID);
                return assigned;
            }
        }
        assigned = findFirstFreeColor();
        siteColorIndices.put(siteID, assigned);
        currentlyTakenColors.put(assigned, siteID);

        return assigned;
    }

    private void releaseColor(int siteID)
    {
        Integer colorToRemove = null;

        // loop over entries not over i = 0 1 2 3 because some colors may not be taken
        for (Map.Entry<Integer, Integer> entry : currentlyTakenColors.entrySet()) {
            if (entry.getValue() == siteID) {
                colorToRemove = entry.getKey();
                break;
            }
        }

        if (colorToRemove != null) {
            currentlyTakenColors.remove(colorToRemove);
        }
    }

    private int findFirstFreeColor()
    {
        for (int i = 0; i < BlankController.USER_COLORS.length; i++) {
            if (!currentlyTakenColors.containsKey(i)) {
                return i;
            }
        }
        return 0; // should never happen
    }

    private boolean isColorAvailForSite(int color, int siteID)
    {
        Integer owner = currentlyTakenColors.get(color);
        if (owner == null || owner == siteID) return true;
        return false;
    }
}
