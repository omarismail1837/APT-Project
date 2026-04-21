package App;

import App.crdt.action.Action;
import App.crdt.block.BlockDLL;
import App.crdt.block.BlockNode;
import App.crdt.character.CharDLL;
import App.crdt.character.CharNode;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;import javafx.scene.control.*;
import javafx.scene.input.KeyCode;import javafx.scene.layout.HBox;import javafx.scene.layout.VBox;import javafx.scene.paint.Color;import javafx.scene.shape.Circle;import org.fxmisc.richtext.StyleClassedTextArea;import org.fxmisc.richtext.model.RichTextChange;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

// Responsible for:
// listening to what user types & translating the keystrokes to CRDT OPs
// send those ops to the server via websocket
// recv ops from other users via websocket & apply to doc
public class BlankController implements Initializable {

    private final int mySiteID = Math.abs(UUID.randomUUID().hashCode());
    private long clock = 0;
    // everyone is connected to the same document (for now)
    private final String docID = "doc-123";
    private WebSocketService wsService;

    private final BlockDLL blockDLL;

    // flat lst of all visible characters in order
    // used to map a cursor position in the TextArea to an actual character in the CRDT
    private final ArrayList<CharNode> visibleNodes = new ArrayList<>();

    // server broadcasts my own edits to everyone including me
    // prevent applying my own edit twice via this hashset
    private final Set<String> seenActionIds = new HashSet<>();

    // when true, the text change listener ignores the change so it doesnt lead to an infinite loop
    private boolean isRemoteUpdate = false;
    private final Map<Integer, String> remoteCursorPositions = new LinkedHashMap<>();
    private final Map<Integer, String> remoteUserNames = new LinkedHashMap<>();
    private final Map<Integer, String> siteColorMap = new HashMap<>();
    private static final String[] USER_COLORS = {"#3b82f6","#10b981","#f59e0b","#8b5cf6"};
    private long lastCursorBroadcastMs = 0;

    @FXML private VBox activeUsersBox;

    @FXML
    private StyleClassedTextArea textArea;
    @FXML
    private Button boldButton;
    @FXML
    private Button italicButton;
    @FXML private Label lineColLabel;
    @FXML private Label connectedLabel;

    public BlankController(BlockDLL blockDLL) {
        this.blockDLL = (blockDLL != null) ? blockDLL : new BlockDLL();
    }

    public BlankController() {
        this(new BlockDLL());
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[CLIENT INIT] mySiteID=" + mySiteID + " docID=" + docID);
        String seedHeadId = ensureSeedHeadIDLocal();
        System.out.println("[CLIENT INIT] seedHeadID=" + seedHeadId);
        ensureSeedHeadMapped();
        repairBlockActionState();
        refreshMapping();
        setUpTextAreaListener();
        textArea.caretPositionProperty().addListener((obs, oldVal, newVal) -> {
            updateLineCol();
            broadcastCursorPosition(newVal.intValue());
        });

        // websocket messages arrive on a background thread, but javafx ui can only be updated from the main thread
        // Platform.runLater() schedules the update to run on the main thread safely
        // accept is a function that runs everytime a webscoket msg arrives
        // anonymous class that implements Consumer<Action> on the spot
        wsService = new WebSocketService(new Consumer<Action>() {
            @Override
            public void accept(final Action action) {
                System.out.println("[CLIENT CALLBACK] received action from websocket=" + action);
                javafx.application.Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        handleRemoteAction(action);
                    }
                });
            }
        });
        wsService.connect("https://apt-project-production-326d.up.railway.app/ws-connect");
        updateActiveUsersPanel();
    }

    private void broadcastCursorPosition(int caretPos) {
        long now = System.currentTimeMillis();
        if (now - lastCursorBroadcastMs < 80) return;
        lastCursorBroadcastMs = now;

        String charID;
        if (visibleNodes.isEmpty() || caretPos == 0) {
            charID = getSeedHeadID();
        } else {
            int idx = Math.min(caretPos, visibleNodes.size()) - 1;
            charID = visibleNodes.get(idx).getCharID();
        }
        if (charID == null) return;

        // moving cursors doesnt inc clock
        Action action = new Action(clock, now, mySiteID, docID,
                "CURSOR", charID, null, "User-" + (mySiteID % 1000));
        wsService.sendAction(action);
    }

    // UnaryOperator<TextFormatter.Change> means a function that takes a change and returns a change
    // return change object to apply it and null to cancel the change
    private void setUpTextAreaListener() {
        textArea.multiRichChanges()
                .filter(changes -> !isRemoteUpdate)
                .subscribe(changes -> {
                    // cancel all changes by re-rendering immediately
                    changes.forEach(change -> processRichChange(change));
                    rerender(textArea.getCaretPosition());
                });
    }

    private void rerender(int preferredCaret) {
        boolean previousRemoteFlag = isRemoteUpdate;
        isRemoteUpdate = true; // make listener ignore the edits
        try {
            refreshMapping();
            // set text BEFORE styling
            textArea.replaceText(blockDLL.collectText());
            applyStyles();

            // preventing a crash when a remote edit shrinks the document below current cursor position
            // Math.min(caret, max) -> don't go past the end of the document (clamp)
            // Math.max(....) -> dont go before the start
            int max = textArea.getLength();
            int safeCaret = Math.max(0, Math.min(preferredCaret, max));
            textArea.selectRange(safeCaret, safeCaret);
        } finally {
            isRemoteUpdate = previousRemoteFlag;
        }
    }

    private void processRichChange(RichTextChange<?, ?, ?> change)
    {
        int idx = change.getPosition();

        // DELETE first
        // to account for selecting text & typing over it
        if (!change.getRemoved().getText().isEmpty()) {
            int deleteCount = change.getRemoved().getText().length();
            for (int i = 0; i < deleteCount; i++) {
                if (idx >= visibleNodes.size()) {
                    break;
                }

                String targetID = visibleNodes.get(idx).getCharID();
                long thisClock = ++clock;
                long now = System.currentTimeMillis();
                String actType = "DELETE";

                Action action = new Action(thisClock, now, mySiteID, docID, actType, targetID, null, null);
                blockDLL.applyAction(action);
                seenActionIds.add(buildActionId(action));
                wsService.sendAction(action);
                refreshMapping();
            }
        }

        // INSERT
        if (!change.getInserted().getText().isEmpty()) {
            ensureSeedHeadMapped();
            String rootID = getSeedHeadID();
            if (rootID == null) {
                return;
            }
            String text = change.getInserted().getText();

            String parentID = resolveParentIDForInsert(idx, rootID);

            for (int i = 0; i < text.length(); i++) {
                char nextChar = text.charAt(i);
                long thisClock = ++clock;
                long now = System.currentTimeMillis();

                CharNode newNode = new CharNode(mySiteID, thisClock, now, nextChar, parentID);

                Action action = new Action(thisClock, now, mySiteID, docID, "INSERT", parentID, null, String.valueOf(nextChar));
                blockDLL.applyAction(action);
                seenActionIds.add(buildActionId(action));
                wsService.sendAction(action);
                parentID = newNode.getCharID();
            }
            refreshMapping();
        }
    }

    // Recv change object which describes what the user just did - either insert, delete, or both
    private void processChange(TextFormatter.Change change) {
        String text = change.getText();
        int idx = change.getRangeStart();

        // DELETE first
        // to account for selecting text & typing over it
        if (change.getRangeStart() < change.getRangeEnd()) {
            int deleteCount = change.getRangeEnd() - change.getRangeStart();
            for (int i = 0; i < deleteCount; i++) {
                if (idx >= visibleNodes.size()) {
                    break;
                }

                String targetID = visibleNodes.get(idx).getCharID();
                long thisClock = ++clock;
                long now = System.currentTimeMillis();
                String actType = "DELETE";

                Action action = new Action(thisClock, now, mySiteID, docID, actType, targetID, null, null);
                blockDLL.applyAction(action);
                seenActionIds.add(buildActionId(action));
                wsService.sendAction(action);
                refreshMapping();
            }
        }

        // INSERT
        if (!text.isEmpty()) {
            ensureSeedHeadMapped();
            String rootID = getSeedHeadID();
            if (rootID == null) {
                return;
            }

            String parentID = resolveParentIDForInsert(idx, rootID);

            for (int i = 0; i < text.length(); i++) {
                char nextChar = text.charAt(i);
                long thisClock = ++clock;
                long now = System.currentTimeMillis();

                CharNode newNode = new CharNode(mySiteID, thisClock, now, nextChar, parentID);

                Action action = new Action(thisClock, now, mySiteID, docID, "INSERT", parentID, null, String.valueOf(nextChar));
                blockDLL.applyAction(action);
                seenActionIds.add(buildActionId(action));
                wsService.sendAction(action);
                parentID = newNode.getCharID();
            }
            refreshMapping();
        }
    }


    // if inserting at position 0 or document is empty, parent is the root (document head)
    // otherwiseparent is the character just before the insertion point in visibleNodes
    private String resolveParentIDForInsert(int textAreaIndex, String rootID) {
        if (visibleNodes.isEmpty()) {
            return rootID;
        }

        // TextArea indices can be larger than visible CRDT chars because block rendering adds line breaks.
        int normalizedIndex = Math.max(0, Math.min(textAreaIndex, visibleNodes.size()));
        if (normalizedIndex == 0) {
            return rootID;
        }

        return visibleNodes.get(normalizedIndex - 1).getCharID();
    }

    private String getSeedHeadID() {
        BlockNode root = blockDLL.getBlock("ROOT");
        if (root == null || root.getNext() == null || root.getNext().getContent() == null) {
            return ensureSeedHeadIDLocal();
        }
        return root.getNext().getContent().getHeadID();
    }

    private String ensureSeedHeadIDLocal() {
        BlockNode root = blockDLL.getBlock("ROOT");
        if (root == null) {
            return null;
        }

        // if a seed block already exists return its ID
        BlockNode first = root.getNext();
        if (first != null && first.getContent() != null) {
            return first.getContent().getHeadID();
        }

        // if no seed block exists yet create one
        // all clients have the same seed
        CharDLL seedContent = new CharDLL(0, 1, 0L);
        BlockNode seedBlock = new BlockNode(0, 2, 0L, seedContent, "ROOT");
        blockDLL.insert(seedBlock);

        BlockNode created = root.getNext();
        if (created == null || created.getContent() == null) {
            return null;
        }
        return created.getContent().getHeadID();
    }

    // makes sure the seed head ID is registered in the charBlockMap
    // so when someone tries to insert the first character with the seed as parent the CRDT can find it
    @SuppressWarnings("unchecked")
    private void ensureSeedHeadMapped() {
        try {
            BlockNode root = blockDLL.getBlock("ROOT");
            if (root == null || root.getNext() == null || root.getNext().getContent() == null) {
                return;
            }

            String seedHeadId = root.getNext().getContent().getHeadID();
            String seedBlockId = root.getNext().getBlockID();

            Field mapField = blockDLL.getClass().getDeclaredField("charBlockMap");
            mapField.setAccessible(true);
            Object raw = mapField.get(blockDLL);
            if (!(raw instanceof Map)) {
                return;
            }
            Map<String, String> charBlockMap = (Map<String, String>) raw;
            charBlockMap.put(seedHeadId, seedBlockId);
        } catch (NoSuchFieldException ignored) {
            // Older/newer BlockDLL variants may rename internals; skip hard failure.
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to ensure seed head mapping", e);
        }
    }

    // Re-render text from CRDT, not from what the user typed
    private void refreshMapping() {
        visibleNodes.clear();
        BlockNode blockPtr = blockDLL.getBlock("ROOT").getNext();
        while (blockPtr != null) {
            if (!blockPtr.isDeleted()) {
                CharNode charPtr = blockPtr.getContent().getHead().getNext();
                while (charPtr != null) {
                    if (!charPtr.getIsDeleted()) {
                        visibleNodes.add(charPtr);
                    }
                    charPtr = charPtr.getNext();
                }
            }
            blockPtr = blockPtr.getNext();
        }
    }

    // runs whenever another user edits
    public void handleRemoteAction(Action incomingAction) {
        System.out.println("[REMOTE] type=" + incomingAction.getActionType()
                + " site=" + incomingAction.getSiteID()
                + " mysite=" + mySiteID);
        // p1: GUARDS
        if (incomingAction == null) {
            return;
        }

        // always passes for now since the id is hardcoded
        if (docID.equals(incomingAction.getDocumentID()) == false) {
            return; // only process actions for OUR document
        }

        String actionId = buildActionId(incomingAction);
        if (seenActionIds.contains(actionId)) {
            return; // duplicate prevention
        }


        // p2: APPLY
        seenActionIds.add(actionId);
        blockDLL.applyAction(incomingAction);
        rerender(textArea.getCaretPosition());
    }

    private void applyRemoteActionCompat(Action action) {
        String type = action.getActionType();
        if (type == null) {
            return;
        }
        type = type.trim().toUpperCase(Locale.ROOT);

        String startCharID = action.getStartCharID();
        String endCharID = action.getEndCharID();
        String extraData = action.getExtraData();
        int siteID = action.getSiteID();
        long clockValue = action.getClock();
        long time = action.getTime();

        switch (type) {
            case "DELETE":
                if (endCharID == null || endCharID.isBlank()) {
                    blockDLL.deleteChar(startCharID, siteID, clockValue, time);
                } else {
                    blockDLL.deleteChars(startCharID, endCharID, siteID, clockValue, time);
                }
                break;
            case "INSERT":
                if (extraData != null && !extraData.isEmpty()) {
                    blockDLL.insertChar(startCharID,
                            new CharNode(siteID, clockValue, time, extraData.charAt(0), startCharID));
                }
                break;
            default:
                // Ignore unknown remote action types in client compatibility mode.
                break;
        }
    }

    // tries to call blockDLL.applyAction()
    // if it crashes with a NullPointerException related to allActions being null -> repair state & try again
    private void applyRemoteActionSafely(Action action) {
        try {
            blockDLL.applyAction(action);
        } catch (NullPointerException ex) {
            if (!isBlockActionStateNullError(ex)) {
                throw ex;
            }

            System.err.println("[CLIENT] Repairing BlockDLL action state after null allActions error.");
            repairBlockActionState();
            blockDLL.applyAction(action);
        }
    }

    // checks if a NullPointerException is specifically about allActions being null
    // returns true or false, used by applyRemoteActionSafely() to decide whether to repair or throw
    private boolean isBlockActionStateNullError(NullPointerException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("allActions") && message.contains("null");
    }

    // call ensureActionsListInitialized() if it exists
    // sett allActions to a new empty list if it's null
    // set appliedActionIds to a new empty set if it's null
    private void repairBlockActionState() {
        invokeNoArgMethodIfExists("ensureActionsListInitialized");
        setFieldIfNull("allActions", new ArrayList<Action>());
        setFieldIfNull("appliedActionIds", new HashSet<String>());
    }

    private void invokeNoArgMethodIfExists(String methodName) {
        try {
            Method method = blockDLL.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(blockDLL);
        } catch (NoSuchMethodException ignored) {
            // Older BlockDLL versions may not have this helper.
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke BlockDLL method: " + methodName, e);
        }
    }

    // sets a private field on BlockDLL using reflection, but only if it's currently null
    // used to initialize allActions and appliedActionIds if they're null
    private void setFieldIfNull(String fieldName, Object value) {
        try {
            Field field = blockDLL.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object current = field.get(blockDLL);
            if (current == null) {
                field.set(blockDLL, value);
            }
        } catch (NoSuchFieldException ignored) {
            // Older BlockDLL versions may not have newer fields like appliedActionIds.
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to repair BlockDLL field: " + fieldName, e);
        }
    }

    private String buildActionId(Action action) {
        if (action == null) {
            return "null";
        }
        return action.getDocumentID() + ":" + action.getSiteID() + ":" + action.getClock();
    }

    @FXML
    private void toggleBold() {
        IndexRange iRange = textArea.getSelection();
        if (iRange.getLength() == 0) return;
        int S = iRange.getStart();
        int E = iRange.getEnd() - 1;

        if (S < 0 || E >= visibleNodes.size()) return;

        String startCharID = visibleNodes.get(S).getCharID();
        String endCharID = visibleNodes.get(E).getCharID();

        boolean allBold = true;
        for (int i = S; i <= E; i++) {
            if (!visibleNodes.get(i).getBold()) {
                allBold = false;
                break;
            }
        }
        String extraData = allBold ? "false" : "true";

        long thisClock = ++clock;
        long now = System.currentTimeMillis();

        Action action = new Action(thisClock, now, mySiteID, docID, "BOLD", startCharID, endCharID, extraData);
        blockDLL.applyAction(action);
        seenActionIds.add(buildActionId(action));
        wsService.sendAction(action);
        rerender(textArea.getCaretPosition());
    }

    @FXML
    private void toggleItalic() {
        IndexRange iRange = textArea.getSelection();
        if (iRange.getLength() == 0) return;
        int S = iRange.getStart();
        int E = iRange.getEnd() - 1;

        if (S < 0 || E >= visibleNodes.size()) return;

        String startCharID = visibleNodes.get(S).getCharID();
        String endCharID = visibleNodes.get(E).getCharID();

        boolean allItalic = true;
        for (int i = S; i <= E; i++) {
            if (!visibleNodes.get(i).getItalic()) {
                allItalic = false;
                break;
            }
        }
        String extraData = allItalic ? "false" : "true";

        long thisClock = ++clock;
        long now = System.currentTimeMillis();

        Action action = new Action(thisClock, now, mySiteID, docID, "ITALIC", startCharID, endCharID, extraData);
        blockDLL.applyAction(action);
        seenActionIds.add(buildActionId(action));
        wsService.sendAction(action);
        rerender(textArea.getCaretPosition());
    }


    private void applyStyles() {
        int docLength = textArea.getLength();
        for (int i = 0; i < visibleNodes.size(); i++) {
            if (i >= docLength) break; // safety check
            CharNode c = visibleNodes.get(i);
            if (c.getBold() && c.getItalic()) {
                textArea.setStyleClass(i, i + 1, "bold-italic");
            } else if (c.getBold()) {
                textArea.setStyleClass(i, i + 1, "bold");
            } else if (c.getItalic()) {
                textArea.setStyleClass(i, i + 1, "italic");
            } else {
                textArea.setStyleClass(i, i + 1, "regular");
            }
        }
        applyRemoteCursorHighlights();
    }

    private void updateLineCol() {
        int caretPos = textArea.getCaretPosition();
        String text = textArea.getText();
        int line = 1, col = 1;

        for (int i = 0; i < caretPos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        lineColLabel.setText("Line " + line + ", Col " + col);
        System.out.println("caret=" + caretPos + " line=" + line + " col=" + col);
        System.out.println("caret=" + caretPos + " textLength=" + text.length() + " first50chars=" + text.substring(0, Math.min(50, text.length())).replace("\n", "\\n"));
    }

    private String colorForSite(int siteID) {
        return siteColorMap.computeIfAbsent(siteID,
                id -> USER_COLORS[Math.abs(id) % USER_COLORS.length]);
    }

    private void updateActiveUsersPanel() {
        if (activeUsersBox == null) return;
        activeUsersBox.getChildren().clear();
        activeUsersBox.getChildren().add(makeUserRow("You", "#ff5f56"));
        for (Map.Entry<Integer, String> e : remoteUserNames.entrySet()) {
            activeUsersBox.getChildren().add(makeUserRow(e.getValue(), colorForSite(e.getKey())));
        }
        connectedLabel.setText((1 + remoteUserNames.size()) + " editors connected");
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

    private void applyRemoteCursorHighlights() {
        Map<String, Integer> charToSite = new HashMap<>();
        for (Map.Entry<Integer, String> e : remoteCursorPositions.entrySet()) {
            charToSite.put(e.getValue(), e.getKey());
        }
        int docLength = textArea.getLength();
        for (int i = 0; i < visibleNodes.size(); i++) {
            if (i >= docLength) break;
            String cid = visibleNodes.get(i).getCharID();
            if (charToSite.containsKey(cid)) {
                int colorIdx = Math.abs(charToSite.get(cid)) % USER_COLORS.length;
                CharNode c = visibleNodes.get(i);
                String base = (c.getBold() && c.getItalic()) ? "bold-italic"
                        : c.getBold()   ? "bold"
                        : c.getItalic() ? "italic"
                        : "regular";
                textArea.setStyleClass(i, i + 1, base + " remote-cursor-" + colorIdx);
            }
        }
    }
}