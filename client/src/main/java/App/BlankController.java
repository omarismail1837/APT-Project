package App;

import App.crdt.action.Action;
import App.crdt.block.BlockDLL;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.StyleClassedTextArea;

import java.net.URL;
import java.util.HashSet;
import java.util.ResourceBundle;
import java.util.Set;

public class BlankController implements Initializable {

    static final String[] USER_COLORS = {"#3b82f6", "#10b981", "#8b5cf6"};
    static final long CURSOR_THROTTLE_MS = 80;
    static final int MAX_REMOTE_EDITORS = 3;
    static final String WS_URL = "https://apt-project-production-326d.up.railway.app/ws-connect";

    private final int mySiteID;
    private final String username;
    private String docName;
    private final BlockDLL blockDLL;

    private long clock = 0;
    private String docID;
    private final boolean canEdit;
    private final String editCode;
    private final String viewCode;

    private WebSocketService wsService;
    private final Set<String> seenActionIds = new HashSet<>();
    private boolean isRemoteUpdate = false;

    private final EditorDocumentController documentController;
    private final PresenceController presenceController;
    private final SessionSyncController sessionSyncController;
    private final VersionHistoryController versionHistoryController;

    @FXML Label nameLabel;
    @FXML VBox activeUsersBox;
    @FXML StyleClassedTextArea textArea;
    @FXML Button boldButton;
    @FXML Button italicButton;
    @FXML Button exportButton;
    @FXML Button saveVersionButton;
    @FXML Button historyButton;
    @FXML Button undoButton;
    @FXML Button redoButton;
    @FXML Label docIdLabel;
    @FXML Label lineColLabel;
    @FXML Label connectedLabel;
    @FXML Label editCodeLabel;
    @FXML Label viewCodeLabel;

    public BlankController(String docID, String docName, String viewCode, String editCode,
                           int mySiteID, BlockDLL blockDLL, boolean canEdit, String username) {
        this.docID = docID;
        this.editCode = editCode;
        this.viewCode = viewCode;
        this.mySiteID = mySiteID;
        this.blockDLL = blockDLL;
        this.canEdit = canEdit;
        this.docName = docName;
        this.username = username;

        this.documentController = new EditorDocumentController(this);
        this.presenceController = new PresenceController(this, documentController);
        this.sessionSyncController = new SessionSyncController(this, documentController, presenceController);
        this.versionHistoryController = new VersionHistoryController(this, documentController);
    }

    public BlankController(BlockDLL blockDLL) {
        this("local-doc", "N/A", "N/A", null, 0, blockDLL, true, null);
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        documentController.initializeDocument();
        documentController.setupTextAreaListener();
        if (canEdit) presenceController.setupCaretListener();
        setupUI();
        sessionSyncController.setupWebSocket();
    }

    private void setupUI() {
        if (nameLabel != null) nameLabel.setText(docName + ".txt");
        if (docIdLabel != null) docIdLabel.setText(docID);
        if (textArea != null)
        {
            textArea.setEditable(canEdit);
            boldButton.setDisable(!canEdit);
            italicButton.setDisable(!canEdit);
            if (undoButton != null) undoButton.setDisable(!canEdit);
            if (redoButton != null) redoButton.setDisable(!canEdit);
        }

        if (!canEdit) textArea.setStyle("-fx-caret-color: transparent;");
        else presenceController.setUpLocalCaretColor();

        setupCodeLabels();
        presenceController.updateActiveUsersPanel();
        presenceController.updateLineCol();
    }

    private void setupCodeLabels() {
        String edit = (editCode != null && !editCode.isBlank()) ? editCode : "(unavailable)";
        String view = (viewCode != null && !viewCode.isBlank()) ? viewCode : "(unavailable)";

        if (editCodeLabel != null) {
            editCodeLabel.setText(canEdit ? "Edit: " + edit : "View only");
            editCodeLabel.setTooltip(new javafx.scene.control.Tooltip(canEdit ? "Edit code: " + edit : "View only"));
        }

        if (viewCodeLabel != null) {
            viewCodeLabel.setText("View: " + view);
            viewCodeLabel.setTooltip(new javafx.scene.control.Tooltip("View code: " + view));
        }
    }

    @FXML
    private void toggleBold() {
        documentController.toggleBold();
    }

    @FXML
    private void toggleItalic() {
        documentController.toggleItalic();
    }

    @FXML
    private void exportDocument() {
        documentController.exportWordDocument();
    }

    @FXML
    private void saveVersion() {versionHistoryController.saveVersion();}

    @FXML
    private void showHistory() {versionHistoryController.showHistory();}

    @FXML
    private void undo() { documentController.undo(); }

    @FXML
    private void redo() { documentController.redo(); }

    public void close() {
        sessionSyncController.close();
    }

    long nextClock() {
        return ++clock;
    }

    // update clock propely when choosing a doc from browse docs
    void observeClock(Action action) {
        if (action == null || action.getSiteID() != mySiteID) return;
        clock = Math.max(clock, action.getClock());
    }

    long now() {
        return System.currentTimeMillis();
    }

    String buildActionId(Action action) {
        if (action == null) return "null";
        return action.getDocumentId() + ":" + action.getSiteID() + ":" + action.getClock();
    }

    void withRemoteFlag(Runnable task) {
        boolean previous = isRemoteUpdate;
        isRemoteUpdate = true;
        try {
            task.run();
        } finally {
            isRemoteUpdate = previous;
        }
    }

    boolean isRemoteUpdate() {
        return isRemoteUpdate;
    }

    boolean replaceRemoteUpdate(boolean newValue) {
        boolean previous = isRemoteUpdate;
        isRemoteUpdate = newValue;
        return previous;
    }

    void setWsService(WebSocketService wsService) {
        this.wsService = wsService;
    }

    WebSocketService getWsService() {
        return wsService;
    }

    Set<String> getSeenActionIds() {
        return seenActionIds;
    }

    int getMySiteID() {
        return mySiteID;
    }

    String getDocName() {
        return docName;
    }

    String getDocID() {
        return docID;
    }

    boolean canEdit() {
        return canEdit;
    }

    BlockDLL getBlockDLL() {
        return blockDLL;
    }

    String getUsername() { return username; }

    EditorDocumentController getDocumentController() {
        return documentController;
    }

    PresenceController getPresenceController() {
        return presenceController;
    }

    public void setDocID(String docID) {
        this.docID = docID;
    }

    public void insertImportedText(String c)
    {
        if (c == null || c.isBlank()) return;
        textArea.replaceText(c);
    }
    public void handleRemoteRestore() {
        javafx.application.Platform.runLater(() -> {
            blockDLL.clear();
            seenActionIds.clear();
            documentController.initializeDocument();
            if (wsService != null) {
                wsService.resubscribeInitialState(docID);
            }
        });
    }
    public void handleRemoteRename(String newName) {
        docName = newName;
        if (nameLabel != null) nameLabel.setText(docName + ".txt");
    }
}
