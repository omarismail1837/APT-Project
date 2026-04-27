package App;

import App.crdt.action.Action;
import App.crdt.block.BlockDLL;
import App.crdt.block.BlockNode;
import App.crdt.character.CharDLL;
import App.crdt.character.CharNode;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.RichTextChange;

import java.net.URL;
import java.util.*;

// Responsible for:
// listening to what user types & translating the keystrokes to CRDT OPs
// send those ops to the server via websocket
// recv ops from other users via websocket & apply to doc
public class BlankController implements Initializable {

    // 1. constants
    private static final String[] USER_COLORS = {"#3b82f6", "#10b981", "#f59e0b", "#8b5cf6"};
    private static final long CURSOR_THROTTLE_MS = 80; // min ms between cursor broadcasts
    private static final int MAX_REMOTE_USERS = 3;


    // 2. constructor and fields
    public BlankController(String docID, String docName, String viewCode, String editCode,
                           int mySiteID, BlockDLL blockDLL, boolean canEdit) {
        this.docID = docID;
        this.editCode = editCode;
        this.viewCode = viewCode;
        this.mySiteID = mySiteID;
        this.blockDLL = blockDLL;
        this.canEdit = canEdit;
        this.docName = docName;
    }

    // convenience constructor for offline testing
    public BlankController(BlockDLL blockDLL) {
        // Chains to the main constructor with placeholder values
        this("local-doc", "N/A", "N/A", null, 0, blockDLL, true);
    }

    // identity
    private final int mySiteID;
    private final String docName;
    private long clock = 0;
    private String docID;
    private boolean canEdit = true;
    private String editCode; // Store the code
    private String viewCode; // Store the view code if available

    // crdt
    private final BlockDLL blockDLL;

    // flat lst of all visible characters in order
    // used to map a cursor position in the TextArea to an actual character in the CRDT
    private final ArrayList<CharNode> visibleNodes = new ArrayList<>();


    // websocket
    private WebSocketService wsService;

    // server broadcasts my own edits to everyone including me
    // prevent applying my own edit twice via this hashset
    private final Set<String> seenActionIds = new HashSet<>();

    // when true, the text change listener ignores the change so it doesnt lead to an infinite loop
    private boolean isRemoteUpdate = false;

    // 3. cursor tracking

    // siteID -> charID the cursor sits before
    private final Map<Integer, String> remoteCursorPositions = new LinkedHashMap<>();
    // siteID -> display name
    private final Map<Integer, String> remoteUserNames = new LinkedHashMap<>();
    // siteID -> color index (0-3), assigned by server via Action.colorIndex
    private final Map<Integer, Integer> siteColorIndices = new HashMap<>();
    private long lastCursorBroadcastMs = 0;


    // 4. fxml bindings
    @FXML private Label nameLabel;
    @FXML private VBox activeUsersBox;
    @FXML private StyleClassedTextArea textArea;
    @FXML private Button boldButton;
    @FXML private Button italicButton;
    @FXML private Label docIdLabel;
    @FXML private Label lineColLabel;
    @FXML private Label connectedLabel;
    @FXML private Label sessionCodeLabel; // Add this for displaying the code


    // 5. init (separated initialise into functions for readability)
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        ensureSeedBlock();
        refreshMapping();

        setUpTextAreaListener();
        setupCaretListener();
        setupUI();
        setupWebSocket();
    }

    private void setupUI() {
        if (nameLabel != null)      nameLabel.setText(docName + ".txt");
        if (docIdLabel != null)     docIdLabel.setText(docID);
        if (textArea != null)       textArea.setEditable(canEdit);

        if (sessionCodeLabel != null) {
            if (canEdit) {
                String view = (viewCode != null && !viewCode.isBlank()) ? viewCode : "(unavailable)";
                sessionCodeLabel.setText("Edit: " + editCode + "  |  View: " + view);
            } else {
                sessionCodeLabel.setText("View only");
            }
        }

        // Color own caret to match our assigned color
        if (textArea != null) {
            textArea.setStyle("-fx-caret-color: " + colorForSite(mySiteID) + ";");
        }

        updateActiveUsersPanel();
    }

    private void setupWebSocket() {
        wsService = new WebSocketService(
                docID,
                action -> javafx.application.Platform.runLater(() -> handleRemoteAction(action)),
                () -> javafx.application.Platform.runLater(() -> broadcastCursorPosition(textArea.getCaretPosition()))
        );

        wsService.setOnDisconnected(() -> javafx.application.Platform.runLater(() -> {
            if (connectedLabel != null) connectedLabel.setText("Disconnected");
        }));

        wsService.connect("https://apt-project-production-326d.up.railway.app/ws-connect");
    }

    // 6. text area listeners
    // UnaryOperator<TextFormatter.Change> means a function that takes a change and returns a change
    // return change object to apply it and null to cancel the change
    private void setUpTextAreaListener() {
        textArea.multiRichChanges()
                .filter(changes -> !isRemoteUpdate)
                .subscribe(changes -> {
                    changes.forEach(change -> processRichChange(change));
                    int caretSnapshot = textArea.getCaretPosition();

                    boolean previousRemoteFlag = isRemoteUpdate;
                    isRemoteUpdate = true;
                    javafx.application.Platform.runLater(() -> {
                        try {
                            rerender(caretSnapshot);
                            broadcastCursorPosition(textArea.getCaretPosition());
                        } finally {
                            isRemoteUpdate = previousRemoteFlag;
                        }
                    });
                });
    }

    private void setupCaretListener() {
        textArea.caretPositionProperty().addListener((obs, oldVal, newVal) -> {
            updateLineCol();
            if (!isRemoteUpdate) {
                broadcastCursorPosition(newVal.intValue());
            }
        });
    }

    // 7. local edits processing
    private void processRichChange(RichTextChange<?, ?, ?> change) {
        int idx = change.getPosition();

        // delete first to handle selecting & typing at once
        if (!change.getRemoved().getText().isEmpty()) {
            int deleteCount = change.getRemoved().getText().length();
            List<CharNode> snapshot = new ArrayList<>(visibleNodes); // stable indices during loop

            for (int i = 0; i < deleteCount; i++) {
                int targetIdx = idx + i;
                if (targetIdx >= snapshot.size()) break;

                Action action = new Action(++clock, now(), mySiteID, docID,
                        "DELETE", snapshot.get(targetIdx).getCharID(), null, null);
                applyAndSend(action);
            }
            refreshMapping();
        }

        // insert
        if (!change.getInserted().getText().isEmpty()) {
            String seedID = getSeedHeadID();
            if (seedID == null) return;

            String parentID = resolveParentIDForInsert(idx, seedID);
            String text = change.getInserted().getText();

            for (int i = 0; i < text.length(); i++) {
                Action action = new Action(++clock, now(), mySiteID, docID,
                        "INSERT", parentID, null, String.valueOf(text.charAt(i)));
                applyAndSend(action);
                refreshMapping();
                parentID = resolveInsertedCharID(clock); // chain parent for next char
            }
        }
    }

    // apply action to local crdt, mark as seen, and send over websocket
    private void applyAndSend(Action action) {
        blockDLL.applyAction(action);
        seenActionIds.add(buildActionId(action));
        wsService.sendAction(action);
    }

    // 8. remote action handlers
    public void handleRemoteAction(Action action) {
        if (action == null) return;
        if (!docID.equals(action.getDocumentId())) return;

        // Track color assignment from server
        if (action.getColorIndex() >= 0) {
            siteColorIndices.put(action.getSiteID(), action.getColorIndex());
        }

        String type = action.getActionType();

        // cursor update (NOT a document edit)
        if ("CURSOR".equals(type)) {
            handleRemoteCursor(action);
            return;
        }

        // user disconnect
        if ("DISCONNECT".equals(type)) {
            handleRemoteDisconnect(action);
            return;
        }

        // cursor remove
        if ("CURSOR_REMOVE".equals(type)) {
            int siteID = action.getSiteID();
            remoteCursorPositions.remove(siteID);
            remoteUserNames.remove(siteID);
            withRemoteFlag(this::refreshUI);
            return;
        }

        // document edit
        String actionId = buildActionId(action);
        if (seenActionIds.contains(actionId)) return;
        seenActionIds.add(actionId);

        blockDLL.applyAction(action);
        rerender(textArea.getCaretPosition());
    }

    private void handleRemoteCursor(Action action) {
        int siteID = action.getSiteID();
        if (siteID == mySiteID) return; // ignore echoes of our own cursor

        // update cursor position
        remoteCursorPositions.put(siteID, action.getStartCharID());

        // update display name if included
        String extra = action.getExtraData();
        if (extra != null && !extra.isBlank()) {
            remoteUserNames.put(siteID, extra);
        } else {
            remoteUserNames.putIfAbsent(siteID, "User-" + Math.abs(siteID % 1000));
        }

        withRemoteFlag(this::refreshUI);
    }

    private void handleRemoteDisconnect(Action action) {
        int siteID = action.getSiteID();
        if (siteID == mySiteID) return;

        remoteCursorPositions.remove(siteID);
        remoteUserNames.remove(siteID);
        withRemoteFlag(this::refreshUI);
    }

    // 9. cursor broadcasting
    private void broadcastCursorPosition(int caretPos) {
        long now = now();
        if (now - lastCursorBroadcastMs < CURSOR_THROTTLE_MS) return;
        lastCursorBroadcastMs = now;

        String charID = resolveCharIDForCaret(caretPos);
        if (charID == null) return;

        Action action = new Action(++clock, now, mySiteID, docID,
                "CURSOR", charID, null, "User-" + (mySiteID % 1000));
        seenActionIds.add(buildActionId(action));
        wsService.sendAction(action);
    }

    private String resolveCharIDForCaret(int caretPos) {
        if (visibleNodes.isEmpty()) return getSeedHeadID();
        int clamped = Math.min(caretPos, visibleNodes.size() - 1);
        return visibleNodes.get(clamped).getCharID();
    }

    // 10. rendering
    // full re-render: rebuild text from CRDT, apply formatting styles, then overlay remote cursor highlights.
    private void rerender(int preferredCaret) {
        withRemoteFlag(() -> {
            refreshMapping();
            textArea.replaceText(blockDLL.collectText());
            applyStyles();

            int safeCaret = Math.max(0, Math.min(preferredCaret, textArea.getLength()));
            textArea.selectRange(safeCaret, safeCaret);
        });
    }

    // applies bold/italic styles to every visible character then overlays remote cursor highlights on top
    private void applyStyles() {
        // build a reverse map, charID → siteID for fast lookup
        Map<String, Integer> cursorCharToSite = new HashMap<>();
        for (Map.Entry<Integer, String> e : remoteCursorPositions.entrySet()) {
            cursorCharToSite.put(e.getValue(), e.getKey());
        }

        int docLength = textArea.getLength();
        for (int i = 0; i < visibleNodes.size() && i < docLength; i++) {
            CharNode c = visibleNodes.get(i);

            // base formatting class
            String baseClass = resolveBaseClass(c);

            // check if a remote cursor sits at this character
            Integer siteAtCursor = cursorCharToSite.get(c.getCharID());
            if (siteAtCursor != null) {
                int colorIdx = colorIndexForSite(siteAtCursor);
                textArea.setStyle(i, i + 1, Arrays.asList(baseClass, "remote-cursor-" + colorIdx));
            } else {
                textArea.setStyleClass(i, i + 1, baseClass);
            }
        }
    }

    private String resolveBaseClass(CharNode c) {
        if (c.getBold() && c.getItalic()) return "bold-italic";
        if (c.getBold())   return "bold";
        if (c.getItalic()) return "italic";
        return "regular";
    }

    // refreshes only the users panel and cursor highlights, without replacing text
    private void refreshUI() {
        updateActiveUsersPanel();
        applyStyles();
    }


    // 11. formatting actions
    @FXML
    private void toggleBold() {
        applyFormattingAction("BOLD", CharNode::getBold);
    }

    @FXML
    private void toggleItalic() {
        applyFormattingAction("ITALIC", CharNode::getItalic);
    }

    private void applyFormattingAction(String type, java.util.function.Function<CharNode, Boolean> getter) {
        IndexRange sel = textArea.getSelection();
        if (sel.getLength() == 0) return;

        int S = sel.getStart();
        int E = sel.getEnd() - 1;
        if (S < 0 || E >= visibleNodes.size()) return;

        // Toggle: if ALL chars in range already have the style, remove it; otherwise apply
        boolean allStyled = true;
        for (int i = S; i <= E; i++) {
            if (!getter.apply(visibleNodes.get(i))) { allStyled = false; break; }
        }

        Action action = new Action(++clock, now(), mySiteID, docID,
                type,
                visibleNodes.get(S).getCharID(),
                visibleNodes.get(E).getCharID(),
                allStyled ? "false" : "true");

        applyAndSend(action);
        rerender(textArea.getCaretPosition());
    }

    // 12. active users panel
    private void updateActiveUsersPanel() {
        if (activeUsersBox == null) return;
        activeUsersBox.getChildren().clear();

        // always show ourselves first
        activeUsersBox.getChildren().add(makeUserRow("You", colorForSite(mySiteID)));

        // show up to MAX_REMOTE_USERS others
        List<Integer> activeSites = new ArrayList<>(remoteCursorPositions.keySet());
        Collections.sort(activeSites);
        activeSites.stream()
                .limit(MAX_REMOTE_USERS)
                .forEach(siteID -> {
                    String name = remoteUserNames.getOrDefault(siteID, "User-" + Math.abs(siteID % 1000));
                    activeUsersBox.getChildren().add(makeUserRow(name, colorForSite(siteID)));
                });

        if (connectedLabel != null) {
            connectedLabel.setText((1 + Math.min(activeSites.size(), MAX_REMOTE_USERS)) + " editors connected");
        }
    }

    private HBox makeUserRow(String name, String color) {
        Circle dot = new Circle(4);
        dot.setFill(Color.web(color));
        Label label = new Label(name);
        label.setStyle("-fx-text-fill: #e0e0e0;");
        HBox row = new HBox(10, dot, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // 13. status bar
    private void updateLineCol() {
        if (lineColLabel == null) return;
        int caretPos = textArea.getCaretPosition();
        String text = textArea.getText();
        int line = 1, col = 1;
        for (int i = 0; i < caretPos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') { line++; col = 1; } else { col++; }
        }
        lineColLabel.setText("Line " + line + ", Col " + col);
    }

    // 14. cleanup
    /**
     * Gracefully close resources associated with this controller.
     * Call this when the window is closed to ensure the websocket disconnects.
     */
    public void close() {
        try {
            if (wsService != null) {
                // Build and send a DISCONNECT action so the server can broadcast it to other clients
                long thisClock = ++clock;
                long now = System.currentTimeMillis();
                String userName = remoteUserNames.getOrDefault(mySiteID, "User-" + (mySiteID % 1000));
                Action action = new Action(thisClock, now, mySiteID, docID, "DISCONNECT", null, null, userName);

                // mark as seen so we don't re-apply our own disconnect when it echoes back
                seenActionIds.add(buildActionId(action));

                // Attempt to send the action. If the session is disconnected this will enqueue it.
                try {
                    wsService.sendAction(action);
                } catch (Exception e) {
                    System.err.println("Failed to send DISCONNECT action: " + e.getMessage());
                }

                // Give a short grace period for the disconnect message to be transmitted, then disconnect.
                new Thread(() -> {
                    try {
                        Thread.sleep(200); // 200ms
                    } catch (InterruptedException ignored) {}
                    try {
                        wsService.disconnect();
                    } catch (Exception ex) {
                        System.err.println("Error while disconnecting WS: " + ex.getMessage());
                    }
                }, "ws-disconnect-thread").start();
            }
        } catch (Exception ex) {
            System.err.println("Error while closing BlankController: " + ex.getMessage());
        }
    }

    // 15. crdt helpers
    private void refreshMapping() {
        visibleNodes.clear();
        BlockNode block = blockDLL.getBlock("ROOT");
        if (block == null) return;
        block = block.getNext();

        while (block != null) {
            if (!block.isDeleted() && block.getContent() != null) {
                CharNode c = block.getContent().getHead().getNext();
                while (c != null) {
                    if (!c.getIsDeleted()) visibleNodes.add(c);
                    c = c.getNext();
                }
            }
            block = block.getNext();
        }
    }

    // ensures a seed block exists so the document always has a valid insertion point.
    private void ensureSeedBlock() {
        BlockNode root = blockDLL.getBlock("ROOT");
        if (root == null) return;
        if (root.getNext() != null && root.getNext().getContent() != null) return; // already exists

        CharDLL seedContent = new CharDLL(0, 1, 0L);
        BlockNode seedBlock = new BlockNode(0, 2, 0L, seedContent, "ROOT");
        blockDLL.insert(seedBlock);
    }

    private String getSeedHeadID() {
        BlockNode root = blockDLL.getBlock("ROOT");
        if (root == null || root.getNext() == null || root.getNext().getContent() == null) return null;
        return root.getNext().getContent().getHeadID();
    }

    private String resolveParentIDForInsert(int textAreaIndex, String rootID) {
        if (visibleNodes.isEmpty() || textAreaIndex == 0) return rootID;
        int idx = Math.min(textAreaIndex, visibleNodes.size()) - 1;
        return visibleNodes.get(idx).getCharID();
    }

    // after insert with given clock, find char id assigned to it
    private String resolveInsertedCharID(long insertClock) {
        refreshMapping();
        for (CharNode node : visibleNodes) {
            if (node.getSiteID() == mySiteID && node.getClock() == insertClock) {
                return node.getCharID();
            }
        }
        return getSeedHeadID(); // should never happen
    }

    // 16. color helpers
    private String colorForSite(int siteID) {
        return USER_COLORS[colorIndexForSite(siteID)];
    }

    private int colorIndexForSite(int siteID) {
        Integer idx = siteColorIndices.getOrDefault(siteID, Math.abs(siteID) % USER_COLORS.length);
        return Math.max(0, Math.min(idx, USER_COLORS.length - 1));
    }

    // 17. utilities
    public void setDocID(String docID) {
        this.docID = docID;
    }
    public String getDocID() {return docID;}
    private String buildActionId(Action action) {
        if (action == null) return "null";
        return action.getDocumentId() + ":" + action.getSiteID() + ":" + action.getClock();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    // runs an update with isRemoteUpdate = true & restores previous flag
    private void withRemoteFlag(Runnable task) {
        boolean prev = isRemoteUpdate;
        isRemoteUpdate = true;
        try { task.run(); }
        finally { isRemoteUpdate = prev; }
    }

}
