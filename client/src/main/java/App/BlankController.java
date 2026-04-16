package App;

import App.crdt.block.BlockDLL;
import App.crdt.block.BlockNode;
import App.crdt.character.CharNode;
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

    // map UI indices to actual CharNodes
    private ArrayList<CharNode> visibleNodes = new ArrayList<>();

    // When edit is remote -> triggers local update -> local update sends message to server -> inf loop
    // prevent echo effect
    private boolean isRemoteUpdate = false;

    @FXML
    private TextArea textArea;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setUpTextAreaListener();
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

        // INSERT
        if (!text.isEmpty()) {
            // Find the ID of the node to the left of the cursor
            String pID = (idx == 0) ? "ROOT" : visibleNodes.get(idx - 1).getCharID();

            int thisClock = ++clock;
            CharNode newNode = new CharNode(mySiteID, thisClock, System.currentTimeMillis(), text.charAt(0), pID);

            // blockDLL handles the insertion logic via charBlockMap
            blockDLL.insertChar(pID, newNode);

            // create the action object
            Action action = new Action(thisClock, mySiteID, docID, "INSERT", pID, null, text);

            // TODO: Send the action to the network

            refreshMapping();
        }

        // DELETE
        if (change.getRangeStart() < change.getRangeEnd()) {
            // The ID of the character currently sitting at this index
            String targetID = visibleNodes.get(idx).getCharID();

            int thisClock = ++clock;
            blockDLL.deleteChar(targetID, mySiteID, thisClock, System.currentTimeMillis());

            // create the action object
            Action action = new Action(thisClock, mySiteID, docID, "DELETE", targetID, null, null);

            // TODO: Send the action to the network

            refreshMapping();
        }

        // verify dll is correct with console
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
        isRemoteUpdate = true;

        // Apply logic
        blockDLL.applyAction(incomingAction);

        // Sync ui
        textArea.setText(blockDLL.collectText());
        refreshMapping();

        isRemoteUpdate = false;
    }
}