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

public class BlankController implements Initializable {

    private final int mySiteID = Math.abs(UUID.randomUUID().hashCode());
    private int clock = 0;
    private final String docID = "doc-123";
    private WebSocketService wsService;

    private final BlockDLL blockDLL;

    private final ArrayList<CharNode> visibleNodes = new ArrayList<>();
    private final Set<String> seenActionIds = new HashSet<>();
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

    private void setUpTextAreaListener() {
        textArea.setTextFormatter(new TextFormatter<TextFormatter.Change>(new UnaryOperator<TextFormatter.Change>() {
            @Override
            public TextFormatter.Change apply(TextFormatter.Change change) {
                if (!isRemoteUpdate && change.isContentChange()) {
                    processChange(change);
                }
                return change;
            }
        }));
    }

    private void processChange(TextFormatter.Change change) {
        String text = change.getText();
        int idx = change.getRangeStart();

        // DELETE first
        if (change.getRangeStart() < change.getRangeEnd()) {
            int deleteCount = change.getRangeEnd() - change.getRangeStart();
            for (int i = 0; i < deleteCount; i++) {
                if (idx >= visibleNodes.size()) {
                    break;
                }

                String targetID = visibleNodes.get(idx).getCharID();
                int thisClock = ++clock;
                long now = System.currentTimeMillis();
                blockDLL.deleteChar(targetID, mySiteID, thisClock, now);

                Action action = new Action(thisClock, now, mySiteID, docID, "DELETE", targetID, null, null);
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

            String parentID = (idx == 0) ? rootID : (idx <= visibleNodes.size() ? visibleNodes.get(idx - 1).getCharID() : rootID);

            for (int i = 0; i < text.length(); i++) {
                char nextChar = text.charAt(i);
                int thisClock = ++clock;
                long now = System.currentTimeMillis();

                CharNode newNode = new CharNode(mySiteID, thisClock, now, nextChar, parentID);
                blockDLL.insertChar(parentID, newNode);

                Action action = new Action(thisClock, now, mySiteID, docID, "INSERT", parentID, null, String.valueOf(nextChar));
                seenActionIds.add(buildActionId(action));
                wsService.sendAction(action);
                parentID = newNode.getCharID();
            }
            refreshMapping();
        }
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

        BlockNode first = root.getNext();
        if (first != null && first.getContent() != null) {
            return first.getContent().getHeadID();
        }

        CharDLL seedContent = new CharDLL(0, 1, 0L);
        BlockNode seedBlock = new BlockNode(0, 2, 0L, seedContent, "ROOT");
        blockDLL.insert(seedBlock);

        BlockNode created = root.getNext();
        if (created == null || created.getContent() == null) {
            return null;
        }
        return created.getContent().getHeadID();
    }

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

    public void handleRemoteAction(Action incomingAction) {
        if (incomingAction == null) {
            return;
        }

        if (docID.equals(incomingAction.getDocumentID()) == false) {
            return;
        }

        String actionId = buildActionId(incomingAction);
        if (seenActionIds.contains(actionId)) {
            return;
        }

        seenActionIds.add(actionId);
        isRemoteUpdate = true;
        try {
            int caret = textArea != null ? textArea.getCaretPosition() : 0;
            int anchor = textArea != null ? textArea.getAnchor() : 0;
            double scrollTop = textArea != null ? textArea.getScrollTop() : 0;
            double scrollLeft = textArea != null ? textArea.getScrollLeft() : 0;

            applyRemoteActionCompat(incomingAction);
            textArea.setText(blockDLL.collectText());

            if (textArea != null) {
                int max = textArea.getLength();
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

    private boolean isBlockActionStateNullError(NullPointerException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("allActions") && message.contains("null");
    }

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