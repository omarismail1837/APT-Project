package App;

import App.crdt.character.CharNode;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

public class CommentController {
    private final BlankController host;

    // Key: "startCharID|endCharID" ensures stability across edits[cite: 5]
    private final Map<String, Comment> comments = new LinkedHashMap<>();

    CommentController(BlankController host) {
        this.host = host;
    }

    void rightClickListener() {
        ContextMenu menu = new ContextMenu();
        MenuItem addCommentItem = new MenuItem("");
        menu.getItems().add(addCommentItem);

        host.textArea.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                int start = host.textArea.getSelection().getStart();
                int end = host.textArea.getSelection().getEnd();

                if (start == end) {
                    addCommentItem.setDisable(true);
                    addCommentItem.setText("\uD83D\uDCAC Add Comment (select text first)");
                } else {
                    addCommentItem.setDisable(false);
                    addCommentItem.setText("\uD83D\uDCAC Add Comment");
                }
                menu.show(host.textArea, event.getScreenX(), event.getScreenY());
            } else {
                menu.hide();
            }
        });

        addCommentItem.setOnAction(e -> {
            IndexRange sel = host.textArea.getSelection();
            if (sel.getStart() != sel.getEnd()) {
                showAddCommentDialog(sel.getStart(), sel.getEnd());
            }
        });
    }

    private void showAddCommentDialog(int start, int end) {
        String selectedText = host.textArea.getText(start, end);
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Add Comment");

        TextArea commentInput = new TextArea();
        commentInput.setPromptText("Write your comment here…");
        Button saveBtn = new Button("Save");
        saveBtn.setDefaultButton(true);

        VBox layout = new VBox(10, new Label("Comment:"), commentInput, saveBtn);
        layout.setPadding(new Insets(18));

        saveBtn.setOnAction(ev -> {
            String text = commentInput.getText().trim();
            if (!text.isEmpty()) {
                CharNode startNode = host.getDocumentController().getVisibleNode(start);
                CharNode endNode = host.getDocumentController().getVisibleNode(end - 1);

                if (startNode != null && endNode != null) {
                    // Stable mapping using CharIDs[cite: 5]
                    String key = startNode.getCharID() + "|" + endNode.getCharID();
                    comments.put(key, new Comment(host.getPresenceController().getDisplayName(), text));

                    host.textArea.selectRange(start, end);
                    host.getDocumentController().highlight(); // Highlight persistent visual range[cite: 5]
                    host.textArea.deselect();
                    refreshCommentsSidebar();

                    // Broadcast the comment via WebSockets
                    if (host.getWsService() != null) {
                        App.crdt.action.Action action = new App.crdt.action.Action(
                                host.nextClock(), host.now(), host.getMySiteID(), host.getDocID(),
                                "COMMENT", startNode.getCharID(), endNode.getCharID(), text
                        );
                        host.getWsService().sendAction(action);
                    }
                }
            }
            dialog.close();
        });

        dialog.setScene(new Scene(layout));
        dialog.showAndWait();
    }

    void receiveRemoteComment(App.crdt.action.Action action) {
        int start = host.getDocumentController().resolveTextAreaIndexForCharID(action.getStartCharID());
        int end = host.getDocumentController().resolveTextAreaIndexForCharID(action.getEndCharID()) + 1;
        if (start == -1 || end == -1 || action.getSiteID() == host.getMySiteID()) return;

        String key = action.getStartCharID() + "|" + action.getEndCharID();
        String senderName = host.getPresenceController().getNameForSite(action.getSiteID());
        comments.put(key, new Comment(senderName, action.getExtraData()));

        host.textArea.selectRange(start, end);
        host.getDocumentController().highlight();
        host.textArea.deselect();
        refreshCommentsSidebar();
    }

    void pruneDeletedComments() {
        boolean changed = false;
        java.util.Iterator<String> it = comments.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            String[] parts = key.split("\\|", 2);
            if (parts.length < 2) { it.remove(); changed = true; continue; }

            // Comment survives if AT LEAST one character in its range is still visible[cite: 5]
            if (!hasVisibleCharactersInRange(parts[0], parts[1])) {
                it.remove();
                changed = true;
            }
        }
        if (changed) refreshCommentsSidebar();
    }

    private boolean hasVisibleCharactersInRange(String startCharID, String endCharID) {
        boolean inRange = false;
        App.crdt.block.BlockNode block = host.getBlockDLL().getBlock("ROOT");
        if (block != null) block = block.getNext();

        while (block != null) {
            if (block.getContent() != null) {
                CharNode c = block.getContent().getHead().getNext();
                while (c != null) {
                    if (c.getCharID().equals(startCharID)) inRange = true;
                    // If we find any character that is not deleted within the bounds, the comment stays[cite: 5]
                    if (inRange && !block.isDeleted() && !c.getIsDeleted()) return true;
                    if (c.getCharID().equals(endCharID)) return false;
                    c = c.getNext();
                }
            }
            block = block.getNext();
        }
        return false;
    }

    void refreshCommentsSidebar() {
        if (host.commentsBox == null) return;
        host.commentsBox.getChildren().clear();
        for (Comment comment : comments.values()) {
            VBox card = new VBox(4);
            card.setStyle("-fx-background-color: #2a2a2a; -fx-background-radius: 6; -fx-padding: 8;");
            Label nameLabel = new Label(comment.username != null ? comment.username : "Anonymous");
            nameLabel.setStyle("-fx-text-fill: #4ade80; -fx-font-size: 11; -fx-font-weight: bold;");
            Label textLabel = new Label(comment.text);
            textLabel.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12;");
            textLabel.setWrapText(true);
            card.getChildren().addAll(nameLabel, textLabel);
            host.commentsBox.getChildren().add(card);
        }
    }

    private String truncateText(String text, int maxLength) {
        return (text == null || text.length() <= maxLength) ? text : text.substring(0, maxLength - 3) + "...";
    }
}