package App;

import App.crdt.action.Action;
import App.crdt.block.BlockDLL;
import App.crdt.block.BlockNode;
import App.crdt.character.CharDLL;
import App.crdt.character.CharNode;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;

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

    @FXML
    private TextArea textArea;

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
    }

    // UnaryOperator<TextFormatter.Change> means a function that takes a change and returns a change
    // return change object to apply it and null to cancel the change
    private void setUpTextAreaListener() {
        textArea.setTextFormatter(new TextFormatter<TextFormatter.Change>(new UnaryOperator<TextFormatter.Change>() {
            @Override
            public TextFormatter.Change apply(TextFormatter.Change change) {
                if (!isRemoteUpdate && change.isContentChange()) {
                    processChange(change);
                    int preferredCaret = change.getRangeStart();
                    if (change.getText() != null && !change.getText().isEmpty()) {
                        // where the cursor should be after the change
                        preferredCaret += change.getText().length();
                    }
                    rerenderFromBlockDLLAfterLocalChange(preferredCaret);
                    // Model already updated; cancel direct TextArea mutation and keep UI model-driven.
                    return null;
                }
                return change;
            }
        }));
    }

    private void rerenderFromBlockDLLAfterLocalChange(int preferredCaret) {
        boolean previousRemoteFlag = isRemoteUpdate;
        isRemoteUpdate = true;
        try {
            double scrollTop = textArea != null ? textArea.getScrollTop() : 0;
            double scrollLeft = textArea != null ? textArea.getScrollLeft() : 0;
            refreshMapping();
            textArea.setText(blockDLL.collectText());
            int max = textArea.getLength();
            int safeCaret = Math.max(0, Math.min(preferredCaret, max));
            textArea.selectRange(safeCaret, safeCaret);
            textArea.setScrollTop(scrollTop);
            textArea.setScrollLeft(scrollLeft);
        } finally {
            isRemoteUpdate = previousRemoteFlag;
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
        isRemoteUpdate = true;
        try {
            // save caret, anchor, and scroll position
            // caret = where the cursor is
            int caret = textArea != null ? textArea.getCaretPosition() : 0;
            // anchor = where a text selection started, if no selection -> anchor = caret
            int anchor = textArea != null ? textArea.getAnchor() : 0;
            double scrollTop = textArea != null ? textArea.getScrollTop() : 0;
            double scrollLeft = textArea != null ? textArea.getScrollLeft() : 0;
            applyRemoteActionCompat(incomingAction);
            textArea.setText(blockDLL.collectText());

            if (textArea != null) {
                int max = textArea.getLength();
                // preventing a crash when a remote edit shrinks the document below current cursor position
                // Math.min(caret, max) -> don't go past the end of the document (clamp)
                // Math.max(....) -> dont go before the start
                int safeCaret = Math.max(0, Math.min(caret, max));
                int safeAnchor = Math.max(0, Math.min(anchor, max));
                textArea.selectRange(safeAnchor, safeCaret);
                textArea.setScrollTop(scrollTop);
                textArea.setScrollLeft(scrollLeft);
            }

            refreshMapping();
        } finally {
            isRemoteUpdate = false;
        }
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
}