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

import java.net.URL;
import java.util.ArrayList;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public class BlankController implements Initializable {

    private final int mySiteID = Math.abs(UUID.randomUUID().hashCode());
    private int clock = 0;
    private final String docID = "doc-123";
    private WebSocketService wsService;

    private BlockDLL blockDLL = new BlockDLL();
    CharDLL content0 = new CharDLL(mySiteID, ++clock, System.currentTimeMillis());
    BlockNode block0 = new BlockNode(mySiteID, ++clock, System.currentTimeMillis(), content0, "ROOT");

    private ArrayList<CharNode> visibleNodes = new ArrayList<CharNode>();
    private boolean isRemoteUpdate = false;

    @FXML
    private TextArea textArea;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[CLIENT INIT] mySiteID=" + mySiteID + " docID=" + docID);
        blockDLL.insert(block0);
        setUpTextAreaListener();

        wsService = new WebSocketService(new Consumer<Action>() {
            @Override
            public void accept(final Action action) {
                System.out.println("[CLIENT CALLBACK] received action from websocket=" + action);
                javafx.application.Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        System.out.println("[CLIENT CALLBACK FX] applying action on JavaFX thread=" + action);
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
            if (idx < visibleNodes.size()) {
                String targetID = visibleNodes.get(idx).getCharID();
                int thisClock = ++clock;
                blockDLL.deleteChar(targetID, mySiteID, thisClock, System.currentTimeMillis());
                final Action action = new Action(thisClock, System.currentTimeMillis(), mySiteID, docID, "DELETE", targetID, null, null);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        wsService.sendAction(action);
                    }
                }).start();
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
            final Action action = new Action(thisClock, System.currentTimeMillis(), mySiteID, docID, "INSERT", pID, null, text);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    wsService.sendAction(action);
                }
            }).start();
            refreshMapping();
        }

        System.out.println("Full Document:\n" + blockDLL.collectText());
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
        System.out.println("[REMOTE HANDLE] incomingSiteID=" + incomingAction.getSiteID() + " mySiteID=" + mySiteID + " action=" + incomingAction);
        if (incomingAction.getSiteID() == mySiteID) {
            System.out.println("[REMOTE HANDLE] skipped because incoming action has same siteID as this client.");
            return;
        }
        isRemoteUpdate = true;
        try {
            System.out.println("[REMOTE HANDLE] applying action...");
            blockDLL.applyAction(incomingAction);
            textArea.setText(blockDLL.collectText());
            refreshMapping();
            System.out.println("[REMOTE HANDLE] applied. textLength=" + textArea.getText().length());
        } finally {
            isRemoteUpdate = false;
            System.out.println("[REMOTE HANDLE] done. isRemoteUpdate reset to false");
        }
    }
}