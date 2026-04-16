package App;

import App.crdt.character.CharDLL;
import App.crdt.character.CharNode;
import App.crdt.action.Action;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;

import java.net.URL;
import java.util.ArrayList;
import java.util.ResourceBundle;

// Implement Initializable to make sure UI is ready (no NullPointerException)
public class BlankController implements Initializable {
    // Hardcode the siteID for now
    private final int mySiteID = 1;
    private int clock = 0;
    // Hardcode the docID for now
    private final String docID = "doc-123";

    // Array to map UI & Nodes
    // Easy lookup
    ArrayList<CharNode> visibleNodes = new ArrayList<CharNode>();
    CharDLL dll = new CharDLL(mySiteID, clock++, System.currentTimeMillis());

    @FXML
    private TextArea textArea;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setUpTextAreaListener();
    }

    // Listen to edits
    private void setUpTextAreaListener()
    {
        textArea.setTextFormatter(new TextFormatter<>(change -> {
            if (change.isContentChange()) {
                processChange(change);
            }
            return change;
        }));
    }

    // Create Action objects
    private void processChange(TextFormatter.Change change) {
        String text = change.getText();
        int idx = change.getRangeStart();

        // If text is not empty, it's an insertion
        if (!text.isEmpty()) {
            char content = text.charAt(0);

            // Find parent
            String pID;
            if (idx == 0) {
                pID = dll.getHeadID(); // Use the DLL's internal ROOT ID
            } else {
                // Get the ID of the character to the left of the cursor
                pID = visibleNodes.get(idx - 1).getCharID();
            }

            // Create and Insert
            // Use currentTimeMillis() for now
            int thisClock = clock++;
            CharNode c = new CharNode(mySiteID, thisClock, System.currentTimeMillis(), content, pID);
            dll.insert(c);
            Action A = new Action(thisClock, mySiteID, docID, "INSERT", pID, null, text);
            // Update visible nodes map
            refreshMapping();

            System.out.println("Current DLL Text: " + dll.collectText());
        }

        // Deletion
        if (change.getRangeStart() < change.getRangeEnd()) {
            // TODO
        }
    }

    private void refreshMapping() {
        visibleNodes.clear();
        CharNode ptr = dll.getHead().getNext();
        while (ptr != null) {
            if (!ptr.isDeleted()) {
                visibleNodes.add(ptr);
            }
            ptr = ptr.getNext();
        }
    }}
