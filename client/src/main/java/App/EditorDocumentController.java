package App;

import App.crdt.action.Action;
import App.crdt.block.BlockNode;
import App.crdt.character.CharDLL;
import App.crdt.character.CharNode;
import javafx.scene.control.Alert;
import javafx.scene.control.IndexRange;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.fxmisc.richtext.model.RichTextChange;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class EditorDocumentController {

    private final BlankController host;
    private final ArrayList<CharNode> visibleNodes = new ArrayList<>();
    private final UndoRedoManager undoRedoManager = new UndoRedoManager();
    private final java.util.List<Action> pendingUndoBatch = new java.util.ArrayList<>();
    private final java.util.Map<String, String> redoRemapTable = new java.util.HashMap<>();
    private boolean suppressUndoPush = false;

    EditorDocumentController(BlankController host) {
        this.host = host;
    }

    void initializeDocument() {
        resetHistory();
        ensureSeedBlock();
        refreshMapping();
    }

    void resetHistory() {
        pendingUndoBatch.clear();
        undoRedoManager.clear();
        redoRemapTable.clear();
    }

    void setupTextAreaListener() {
        host.textArea.multiRichChanges()
                .filter(changes -> !host.isRemoteUpdate())
                .subscribe(changes -> {
                    pendingUndoBatch.clear();                          // start fresh batch
                    changes.forEach(this::processRichChange);
                    if (!pendingUndoBatch.isEmpty()) {
                        undoRedoManager.pushUndo(new java.util.ArrayList<>(pendingUndoBatch));
                        redoRemapTable.clear();
                    }
                    int caretSnapshot = host.textArea.getCaretPosition();
                    javafx.application.Platform.runLater(() -> {
                        rerender(caretSnapshot, caretSnapshot);
                        host.getPresenceController().broadcastCursorPosition(host.textArea.getCaretPosition(), true);
                    });
                });
    }

    void processRichChange(RichTextChange<?, ?, ?> change) {
        int idx = change.getPosition();

        if (!change.getRemoved().getText().isEmpty()) {
            int deleteCount = change.getRemoved().getText().length();
            List<CharNode> snapshot = new ArrayList<>(visibleNodes);

            for (int i = 0; i < deleteCount; i++) {
                int targetIdx = idx + i;
                if (targetIdx >= snapshot.size()) break;

                Action action = new Action(host.nextClock(), host.now(), host.getMySiteID(), host.getDocID(),
                        "DELETE", snapshot.get(targetIdx).getCharID(), null, null);
                applyAndTrack(action);
            }
            refreshMapping();
        }
        host.getPresenceController().setLastDeletionStart(idx);

        if (!change.getInserted().getText().isEmpty()) {
            String seedID = getSeedHeadID();
            if (seedID == null) return;

            String parentID = resolveParentIDForInsert(idx, seedID);
            String text = change.getInserted().getText();

            for (int i = 0; i < text.length(); i++) {
                long clock = host.nextClock();
                Action action = new Action(clock, host.now(), host.getMySiteID(), host.getDocID(),
                        "INSERT", parentID, null, String.valueOf(text.charAt(i)));
                applyAndTrack(action);
                refreshMapping();
                parentID = resolveInsertedCharID(clock);
            }
        }
    }

    void applyAndSend(Action action) {
        host.getBlockDLL().applyAction(action);
        host.getSeenActionIds().add(host.buildActionId(action));
        if (host.getWsService() != null) {
            host.getWsService().sendAction(action);
        }
    }

    void rerender(int preferredCaret, int preferredAnchor) {
        host.withRemoteFlag(() -> {
            refreshMapping();
            host.textArea.replaceText(host.getBlockDLL().collectText());
            applyStyles();

            int safeAnchor = Math.max(0, Math.min(preferredAnchor, host.textArea.getLength()));
            int safeCaret = Math.max(0, Math.min(preferredCaret, host.textArea.getLength()));
            host.textArea.selectRange(safeAnchor, safeCaret);

            javafx.application.Platform.runLater(() -> host.getPresenceController().updateRemoteCarets());
        });
    }

    void applyStyles() {
        int docLength = host.textArea.getLength();
        for (int i = 0; i < visibleNodes.size() && i < docLength; i++) {
            CharNode node = visibleNodes.get(i);
            String styleClass = resolveBaseClass(node);
            host.textArea.setStyleClass(i, i + 1, styleClass);
        }
    }

    void toggleBold() {
        applyFormattingAction("BOLD", CharNode::getBold);
    }

    void toggleItalic() {
        applyFormattingAction("ITALIC", CharNode::getItalic);
    }

    void highlight() {
        applyFormattingAction("HIGHLIGHT", CharNode::getHighlighted);
    }

    void exportDocument() {
        Window window = host.textArea != null && host.textArea.getScene() != null
                ? host.textArea.getScene().getWindow()
                : null;
        if (window == null) {
            showExportAlert(Alert.AlertType.ERROR, "Export failed", "The save dialog could not be opened.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export document");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.docx"));
        chooser.setInitialFileName(defaultExportFileName());

        java.nio.file.Path targetPath;
        try {
            java.io.File selectedFile = chooser.showSaveDialog(window);
            if (selectedFile == null) return;

            targetPath = selectedFile.toPath();
            Files.writeString(targetPath, host.getBlockDLL().collectText(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            showExportAlert(Alert.AlertType.ERROR, "Export failed", "Could not save the text file.");
            return;
        }

        showExportAlert(Alert.AlertType.INFORMATION, "Export complete",
                "Saved " + targetPath.getFileName() + " to your computer.");
    }

    void exportWordDocument() {
        Window window = host.textArea != null && host.textArea.getScene() != null
                ? host.textArea.getScene().getWindow()
                : null;

        if (window == null) {
            showExportAlert(Alert.AlertType.ERROR, "Export failed", "The save dialog could not be opened.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export document");
        // Change filter to Word documents
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Word Documents", "*.docx"));
        chooser.setInitialFileName(defaultExportFileName().replace(".txt", ".docx"));

        java.io.File selectedFile = chooser.showSaveDialog(window);
        if (selectedFile == null) return;

        // Use Try-with-resources to ensure the document and stream close properly
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(selectedFile)) {
             XWPFParagraph paragraph = doc.createParagraph();

            // call el new formatting function to fill the paragraph with runs
            host.getBlockDLL().collectFormattedText(paragraph);

            // ne write el actual file data
            doc.write(out);

            showExportAlert(Alert.AlertType.INFORMATION, "Export complete",
                    "Saved " + selectedFile.getName() + " to your computer.");

        } catch (IOException ex) {
            showExportAlert(Alert.AlertType.ERROR, "Export failed", "Could not save the Word file.");
        }
    }

    int resolveTextAreaIndexForCharID(String charID) {
        if (charID == null || charID.equals(getSeedHeadID())) {
            return 0;
        }

        for (int i = 0; i < visibleNodes.size(); i++) {
            if (charID.equals(visibleNodes.get(i).getCharID())) {
                return i + 1;
            }
        }

        return -1; // character was deleted
    }

    String resolveCharIDForCaret(int caretPos) {
        if (visibleNodes.isEmpty()) return getSeedHeadID();
        if (caretPos <= 0) return getSeedHeadID();

        int anchorIndex = Math.min(caretPos, visibleNodes.size()) - 1; // already has this
        if (anchorIndex < 0 || anchorIndex >= visibleNodes.size()) return getSeedHeadID(); // add this
        return visibleNodes.get(anchorIndex).getCharID();
    }

    void refreshUI() {
        host.getPresenceController().updateActiveUsersPanel();
        applyStyles();
        host.getPresenceController().updateRemoteCarets();
    }

    void refreshMapping() {
        visibleNodes.clear();
        BlockNode block = host.getBlockDLL().getBlock("ROOT");
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

    private void applyFormattingAction(String type, java.util.function.Function<CharNode, Boolean> getter) {
        IndexRange selection = host.textArea.getSelection();
        if (selection.getLength() == 0) return;

        int start = selection.getStart();
        int end = selection.getEnd() - 1;
        if (start < 0 || end >= visibleNodes.size()) return;

        boolean allStyled = true;
        for (int i = start; i <= end; i++) {
            if (!getter.apply(visibleNodes.get(i))) {
                allStyled = false;
                break;
            }
        }

        Action action = new Action(host.nextClock(), host.now(), host.getMySiteID(), host.getDocID(),
                type,
                visibleNodes.get(start).getCharID(),
                visibleNodes.get(end).getCharID(),
                allStyled ? "false" : "true");

        pendingUndoBatch.clear();
        applyAndTrack(action);
        undoRedoManager.pushUndo(new ArrayList<>(pendingUndoBatch));
        redoRemapTable.clear();
        pendingUndoBatch.clear();
        int caretSnapshot = host.textArea.getCaretPosition();
        int anchorSnapshot = host.textArea.getAnchor();
        rerender(caretSnapshot, anchorSnapshot);
    }

    private String defaultExportFileName() {
        String baseName = (host.getDocName() == null || host.getDocName().isBlank())
                ? "document"
                : host.getDocName().trim();
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            return baseName;
        }
        return baseName + ".txt";
    }

    private void showExportAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        if (host.textArea != null && host.textArea.getScene() != null) {
            alert.initOwner(host.textArea.getScene().getWindow());
        }
        alert.showAndWait();
    }

    private String resolveBaseClass(CharNode node) {
        String base;
        if (node.getBold() && node.getItalic()) base = "bold-italic";
        else if (node.getBold()) base = "bold";
        else if (node.getItalic()) base = "italic";
        else base = "regular";

        return node.getHighlighted() ? base + "-highlighted" : base;
    }

    private void ensureSeedBlock() {
        BlockNode root = host.getBlockDLL().getBlock("ROOT");
        if (root == null) return;
        if (root.getNext() != null && root.getNext().getContent() != null) return;

        CharDLL seedContent = new CharDLL(0, 1, 0L);
        BlockNode seedBlock = new BlockNode(0, 2, 0L, seedContent, "ROOT");
        host.getBlockDLL().insert(seedBlock);
    }

    String getSeedHeadID() {
        BlockNode root = host.getBlockDLL().getBlock("ROOT");
        if (root == null || root.getNext() == null || root.getNext().getContent() == null) return null;
        return root.getNext().getContent().getHeadID();
    }

    private String resolveParentIDForInsert(int textAreaIndex, String rootID) {
        if (visibleNodes.isEmpty() || textAreaIndex == 0) return rootID;
        int idx = Math.min(textAreaIndex, visibleNodes.size()) - 1;
        return visibleNodes.get(idx).getCharID();
    }

    private String resolveInsertedCharID(long insertClock) {
        refreshMapping();
        for (CharNode node : visibleNodes) {
            if (node.getSiteID() == host.getMySiteID() && node.getClock() == insertClock) {
                return node.getCharID();
            }
        }
        return getSeedHeadID();
    }

    private void applyAndTrack(Action action) {
        host.getBlockDLL().applyAction(action);
        host.getSeenActionIds().add(host.buildActionId(action));
        pendingUndoBatch.add(action);
        if (host.getWsService() != null) {
            host.getWsService().sendAction(action);
        }
    }

    void undo() {
        List<Action> batch = undoRedoManager.popUndo();
        if (batch == null) return;

        for (int i = batch.size() - 1; i >= 0; i--) {
            Action inv = buildInverse(batch.get(i));
            if (inv == null) continue;
            host.getBlockDLL().applyAction(inv);
            host.getSeenActionIds().add(host.buildActionId(inv));
            if (host.getWsService() != null) host.getWsService().sendAction(inv);
        }

        undoRedoManager.pushRedo(batch);

        int caret = host.textArea.getCaretPosition();
        rerender(caret, caret);
    }

    void redo() {
        List<Action> batch = undoRedoManager.popRedo();
        if (batch == null) return;

        List<Action> reappliedBatch = new java.util.ArrayList<>();

        for (Action orig : batch) {
            Action reinsertion = buildReinsertion(orig, redoRemapTable);
            if (reinsertion == null) continue;
            host.getBlockDLL().applyAction(reinsertion);
            host.getSeenActionIds().add(host.buildActionId(reinsertion));
            if (host.getWsService() != null) host.getWsService().sendAction(reinsertion);
            reappliedBatch.add(reinsertion);

            if ("INSERT".equals(orig.getActionType())) {
                redoRemapTable.put(originalCharID(orig), appliedCharID(reinsertion));
            }
        }

        undoRedoManager.pushUndoKeepRedo(reappliedBatch);

        int caret = host.textArea.getCaretPosition();
        rerender(caret, caret);
    }

    private Action buildInverse(Action orig) {
        long newClock = host.nextClock();
        long now      = host.now();
        int  site     = host.getMySiteID();
        String doc    = host.getDocID();

        switch (orig.getActionType()) {
            case "INSERT":
                return new Action(newClock, now, site, doc,
                        "DELETE", appliedCharID(orig), null, null);

            case "DELETE":
                return new Action(newClock, now, site, doc,
                        "UNDELETE", orig.getStartCharID(), null, null);

            case "UNDELETE":
                return new Action(newClock, now, site, doc,
                        "DELETE", orig.getStartCharID(), null, null);

            case "BOLD":
            case "ITALIC":
            case "HIGHLIGHT": {
                String flipped = "true".equalsIgnoreCase(orig.getExtraData()) ? "false" : "true";
                return new Action(newClock, now, site, doc,
                        orig.getActionType(),
                        orig.getStartCharID(), orig.getEndCharID(), flipped);
            }

            default:
                return null;
        }
    }
    private App.crdt.character.CharNode findNodeByID(String charID) {
        if (charID == null) return null;
        App.crdt.block.BlockNode block = host.getBlockDLL().getBlock("ROOT");
        if (block == null) return null;
        block = block.getNext();
        while (block != null) {
            if (block.getContent() != null) {
                App.crdt.character.CharNode c = block.getContent().getHead().getNext();
                while (c != null) {
                    if (charID.equals(c.getCharID())) return c;
                    c = c.getNext();
                }
            }
            block = block.getNext();
        }
        return null;
    }

    private Action buildReinsertion(Action orig, java.util.Map<String, String> remappedCharIds) {
        long newClock = host.nextClock();
        long now      = host.now();
        int  site     = host.getMySiteID();
        String doc    = host.getDocID();

        switch (orig.getActionType()) {
            case "INSERT":
                return new Action(newClock, now, site, doc,
                        "INSERT", remapCharID(orig.getStartCharID(), remappedCharIds), null, orig.getExtraData());

            case "DELETE":
                return new Action(newClock, now, site, doc,
                        "DELETE", remapCharID(orig.getStartCharID(), remappedCharIds), null, null);

            case "UNDELETE":
                return new Action(newClock, now, site, doc,
                        "UNDELETE", remapCharID(orig.getStartCharID(), remappedCharIds), null, null);

            case "BOLD":
            case "ITALIC":
                return new Action(newClock, now, site, doc,
                        orig.getActionType(),
                        remapCharID(orig.getStartCharID(), remappedCharIds),
                        remapCharID(orig.getEndCharID(), remappedCharIds),
                        orig.getExtraData());

            default:
                return null;
        }
    }

    private String remapCharID(String charID, java.util.Map<String, String> remappedCharIds) {
        if (charID == null || remappedCharIds == null || remappedCharIds.isEmpty()) return charID;
        return remappedCharIds.getOrDefault(charID, charID);
    }

    private String originalCharID(Action action) {
        if (action == null) return null;
        return action.getSiteID() + "-" + action.getClock();
    }

    private String appliedCharID(Action action) {
        if (action == null) return null;
        return action.getSiteID() + "-" + action.getClock();
    }

    // for commentcontroller
    CharNode getVisibleNode(int index) {
        if (index < 0 || index >= visibleNodes.size()) return null;
        return visibleNodes.get(index);
    }

    void pasteWithFormatting(int replaceStart, int replaceEnd,
                             String text, List<boolean[]> snapshot) {
        suppressUndoPush = true;
        pendingUndoBatch.clear();

        try {
            host.textArea.replaceText(replaceStart, replaceEnd, text);
        } finally {
            javafx.application.Platform.runLater(() -> {
                try {
                    // 2. Apply formatting — adds BOLD/ITALIC actions to same batch
                    if (snapshot != null && !snapshot.isEmpty()) {
                        int insertEnd = replaceStart + text.length();
                        applyFormattingSnapshot(replaceStart, insertEnd, snapshot, false);
                    }
                } finally {
                    // 3. Push the entire batch as ONE undo entry
                    suppressUndoPush = false;
                    if (!pendingUndoBatch.isEmpty()) {
                        undoRedoManager.pushUndo(new ArrayList<>(pendingUndoBatch));
                        redoRemapTable.clear();
                        pendingUndoBatch.clear();
                    }
                    int caret = host.textArea.getCaretPosition();
                    rerender(caret, caret); // single rerender at the very end
                }
            });
        }
    }


    List<boolean[]> snapshotSelectionFormatting() {
        IndexRange selection = host.textArea.getSelection();
        if (selection.getLength() == 0) return null;

        int start = selection.getStart();
        int end = selection.getEnd(); // exclusive

        List<boolean[]> snapshot = new ArrayList<>();
        for (int i = start; i < end && i < visibleNodes.size(); i++) {
            CharNode node = visibleNodes.get(i);
            snapshot.add(new boolean[]{ node.getBold(), node.getItalic() });
        }
        return snapshot;
    }


    void applyFormattingSnapshot(int insertStart, int insertEnd,
                                 List<boolean[]> snapshot, boolean doRerender) {
        if (snapshot == null || snapshot.isEmpty()) return;
        if (insertStart < 0 || insertEnd > visibleNodes.size()) return;

        int len = Math.min(insertEnd - insertStart, snapshot.size());
        applyFormattingRuns(insertStart, len, snapshot, 0, "BOLD");
        applyFormattingRuns(insertStart, len, snapshot, 1, "ITALIC");

        if (doRerender) {
            int caret = host.textArea.getCaretPosition();
            rerender(caret, caret);
        }
    }

    private void applyFormattingRuns(int insertStart, int len,
                                     List<boolean[]> snapshot,
                                     int formatIndex, String actionType) {
        int runStart = -1;
        for (int i = 0; i <= len; i++) {
            boolean active = i < len && snapshot.get(i)[formatIndex];
            if (active && runStart == -1) {
                runStart = i;
            } else if (!active && runStart != -1) {
                CharNode startNode = visibleNodes.get(insertStart + runStart);
                CharNode endNode   = visibleNodes.get(insertStart + i - 1);
                Action action = new Action(
                        host.nextClock(), host.now(), host.getMySiteID(), host.getDocID(),
                        actionType,
                        startNode.getCharID(),
                        endNode.getCharID(),
                        "true"
                );
                applyAndTrack(action); // just accumulates into pendingUndoBatch
                runStart = -1;
            }
        }
    }
}
