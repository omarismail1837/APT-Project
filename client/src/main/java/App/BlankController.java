package App;

import App.crdt.block.BlockDLL;
import App.crdt.block.BlockNode;
import App.crdt.character.CharDLL;import App.crdt.character.CharNode;
import App.crdt.action.Action;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;

import java.net.URL;
import java.util.ArrayList;
import java.util.ResourceBundle;

public class BlankController implements Initializable {

    // hardcoded data
    private final int mySiteID = 1;
    private int clock = 0;
    private final String docID = "doc-123";

    // any doc starts as one block
    // expands into multiple blocks as text gets added via splitting
    private BlockDLL blockDLL = new BlockDLL();
    CharDLL content0 = new CharDLL(mySiteID, ++clock, System.currentTimeMillis());
    BlockNode block0 = new BlockNode(mySiteID, ++clock, System.currentTimeMillis(), content0, "ROOT");
    private WebSocketService wsService;

    // map UI indices to actual CharNodes
    private ArrayList<CharNode> visibleNodes = new ArrayList<>();

    // When edit is remote -> triggers local update -> local update sends message to server -> inf loop
    // prevent echo effect
    private boolean isRemoteUpdate = false;

    @FXML
    private TextArea textArea;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        blockDLL.insert(block0);
        setUpTextAreaListener();

        wsService = new WebSocketService(action -> {
            javafx.application.Platform.runLater(() -> handleRemoteAction(action));
        });
        wsService.connect("http://localhost:8080/ws-connect");
    }

    private void setUpTextAreaListener() {
        textArea.setTextFormatter(new TextFormatter<>(change -> {
            // only process when the user is actually typing, not when the system is syncing
            if (!isRemoteUpdate && change.isContentChange()) {
                processChange(change);
            }
            return change;
        }));
    }

    private void processChange(TextFormatter.Change change) {
        String text = change.getText();
        int idx = change.getRangeStart();

        if (change.getRangeStart() < change.getRangeEnd()) {
            if (idx < visibleNodes.size()) {
                String targetID = visibleNodes.get(idx).getCharID();
                int thisClock = ++clock;
                blockDLL.deleteChar(targetID, mySiteID, thisClock, System.currentTimeMillis());
                Action action = new Action(thisClock, mySiteID, docID, "DELETE", targetID, null, null);
                new Thread(() -> wsService.sendAction(action)).start();
                refreshMapping();
            }
        }

        // INSERT
        if (!text.isEmpty()) {
            String pID;
            String rootID = block0.getContent().getHeadID();
            pID = (idx == 0) ? rootID : (idx <= visibleNodes.size() ? visibleNodes.get(idx - 1).getCharID() : rootID);

            int thisClock = ++clock;
            CharNode newNode = new CharNode(mySiteID, thisClock, System.currentTimeMillis(), text.charAt(0), pID);
            blockDLL.insertChar(pID, newNode);
            Action action = new Action(thisClock, mySiteID, docID, "INSERT", pID, null, text);
            new Thread(() -> wsService.sendAction(action)).start();
            refreshMapping();
        }

        System.out.println("Full Document:\n" + blockDLL.collectText());
    }

     // Rebuild the visibleNodes list by walking through the entire Block hierarchy
    // Didn't just manually use add() because a single character insertion can, for example, create an autosplit
    // Still have to rethink this for the sake of efficiency
    private void refreshMapping() {
        visibleNodes.clear();

        // start at the block level
        BlockNode blockPtr = blockDLL.getBlock("ROOT").getNext();

        while (blockPtr != null) {
            if (!blockPtr.isDeleted()) {
                // dive into the character level of this block
                CharNode charPtr = blockPtr.getContent().getHead().getNext();
                while (charPtr != null) {
                    if (!charPtr.isDeleted()) {
                        visibleNodes.add(charPtr);
                    }
                    charPtr = charPtr.getNext();
                }
            }
            blockPtr = blockPtr.getNext();
        }
    }

    // TODO: Receive messages from server
    public void handleRemoteAction(Action incomingAction) {
        if (incomingAction.getSiteID() == mySiteID) return;
        isRemoteUpdate = true;

        // Apply logic
        blockDLL.applyAction(incomingAction);

        // Sync ui
        textArea.setText(blockDLL.collectText());
        refreshMapping();

        isRemoteUpdate = false;
    }
}