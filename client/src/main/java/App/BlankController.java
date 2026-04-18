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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public class BlankController implements Initializable {

    private final int mySiteID = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    private int clock = 0;
    private final String docID = "doc-123";
    private WebSocketService wsService;

    private BlockDLL blockDLL = new BlockDLL();

    private ArrayList<CharNode> visibleNodes = new ArrayList<CharNode>();
    private boolean isRemoteUpdate = false;

    @FXML
    private TextArea textArea;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setUpTextAreaListener();

        wsService = new WebSocketService(new Consumer<Action>() {
            @Override
            public void accept(final Action action) {
                javafx.application.Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        handleRemoteAction(action);
                    }
                });
            }
        });
        String wsUrl = System.getProperty("ws.url", "https://apt-project-production-326d.up.railway.app/ws-connect");
        wsService.connect(wsUrl);
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
            String rootID = getRootCharID();
            if (rootID == null) {
                return;
            }
            String pID = (idx == 0) ? rootID : (idx <= visibleNodes.size() ? visibleNodes.get(idx - 1).getCharID() : rootID);

            for (int i = 0; i < text.length(); i++) {
                int thisClock = ++clock;
                CharNode newNode = new CharNode(mySiteID, thisClock, System.currentTimeMillis(), text.charAt(i), pID);
                blockDLL.insertChar(pID, newNode);

                final Action action = new Action(thisClock, System.currentTimeMillis(), mySiteID, docID, "INSERT", pID, null, String.valueOf(text.charAt(i)));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        wsService.sendAction(action);
                    }
                }).start();

                pID = newNode.getCharID();
            }
            refreshMapping();
        }

        System.out.println("Full Document:\n" + blockDLL.collectText());
    }

    private String getRootCharID() {
        BlockNode root = blockDLL.getBlock("ROOT");
        if (root == null) {
            return null;
        }

        BlockNode firstBlock = root.getNext();
        if (firstBlock == null || firstBlock.getContent() == null) {
            return null;
        }
        return firstBlock.getContent().getHeadID();
    }

    private void refreshMapping() {
        visibleNodes.clear();
        BlockNode root = blockDLL.getBlock("ROOT");
        if (root == null) {
            return;
        }

        BlockNode blockPtr = root.getNext();
        while (blockPtr != null) {
            if (!blockPtr.isDeleted()) {
                CharDLL content = blockPtr.getContent();
                if (content == null || content.getHead() == null) {
                    blockPtr = blockPtr.getNext();
                    continue;
                }

                CharNode charPtr = content.getHead().getNext();
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
        if (incomingAction.getSiteID() == mySiteID) {
            return;
        }

        isRemoteUpdate = true;
        try {
            blockDLL.applyAction(incomingAction);
            if (textArea != null) {
                textArea.setText(blockDLL.collectText());
            }
            refreshMapping();
        } finally {
            isRemoteUpdate = false;
        }
    }
}