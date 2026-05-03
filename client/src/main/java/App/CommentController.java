package App;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

public class CommentController {
    private final BlankController host;
    private final Map<String, Comment> comments = new LinkedHashMap<>();

    CommentController(BlankController host) {
        this.host = host;
    }

    void rightClickListener()
    {
        ContextMenu menu = new ContextMenu();
        MenuItem addCommentItem = new MenuItem("");
        menu.getItems().add(addCommentItem);

        host.textArea.setOnMousePressed(event -> {
            // if right click
            if (event.getButton() == MouseButton.SECONDARY) {
                int start = host.textArea.getSelection().getStart();
                int end = host.textArea.getSelection().getEnd();

                if (start == end) {
                    addCommentItem.setDisable(true);
                    // code of speech bubble + text
                    addCommentItem.setText("\uD83D\uDCAC  Add Comment  (select text first)");
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
            if (sel.getStart() == sel.getEnd()) return;
            showAddCommentDialog(sel.getStart(), sel.getEnd());
        });
    }

    private void showAddCommentDialog(int start, int end) {
        String selectedText = host.textArea.getText(start, end);

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Add Comment");
        dialog.setResizable(false);

        // preview of txt
        Label preview = new Label("\"" + truncateText(selectedText, 60) + "\"");
        preview.setStyle("-fx-font-style: italic; -fx-text-fill: #555; -fx-font-size: 12px;");
        preview.setWrapText(true);

        TextArea commentInput = new TextArea();
        commentInput.setPromptText("Write your comment here…");
        commentInput.setPrefRowCount(4);
        commentInput.setWrapText(true);

        Button saveBtn   = new Button("Save");
        Button cancelBtn = new Button("Cancel");
        saveBtn.setDefaultButton(true);
        cancelBtn.setCancelButton(true);
        saveBtn.setStyle(
                "-fx-background-color: #2563eb; -fx-text-fill: white; " +
                        "-fx-font-weight: bold; -fx-background-radius: 6; -fx-padding: 6 18;"
        );
        cancelBtn.setStyle("-fx-background-radius: 6; -fx-padding: 6 14;");

        HBox buttons = new HBox(8, saveBtn, cancelBtn);
        VBox layout  = new VBox(10, new Label("Selected text:"), preview,
                new Label("Comment:"), commentInput, buttons);
        layout.setPadding(new Insets(18));
        layout.setPrefWidth(380);

        saveBtn.setOnAction(ev -> {
            String text = commentInput.getText().trim();
            if (!text.isEmpty()) {
                String key = start + "-" + end;
                comments.put(key, new Comment(host.getPresenceController().getDisplayName(), text));                // highlight the annotated range
                host.textArea.selectRange(start, end);
                host.getDocumentController().highlight();
                refreshCommentsSidebar();
            }

            // broadcast comment
            if (host.getWsService() != null) {
                String startCharID = host.getDocumentController().getVisibleNode(start).getCharID();
                String endCharID = host.getDocumentController().getVisibleNode(end - 1).getCharID();
                App.crdt.action.Action action = new App.crdt.action.Action(
                        host.nextClock(), host.now(), host.getMySiteID(), host.getDocID(),
                        "COMMENT", startCharID, endCharID, text
                );
                host.getWsService().sendAction(action);
            }

            dialog.close();
            // refresh sidebar here inshallah
        });

        cancelBtn.setOnAction(ev -> dialog.close());

        dialog.setScene(new Scene(layout));
        dialog.showAndWait();
    }

    void receiveRemoteComment(App.crdt.action.Action action) {
        int start = host.getDocumentController().resolveTextAreaIndexForCharID(action.getStartCharID());
        int end = host.getDocumentController().resolveTextAreaIndexForCharID(action.getEndCharID()) + 1;
        if (start == -1 || end == -1) return;

        if (action.getSiteID() == host.getMySiteID()) return; // ignore my comments

        String commentText = action.getExtraData();
        String senderName = host.getPresenceController().getNameForSite(action.getSiteID());
        comments.put(start + "-" + end, new Comment(senderName, commentText));
        host.textArea.selectRange(start, end);
        host.getDocumentController().highlight();
        refreshCommentsSidebar();
    }

    void refreshCommentsSidebar() {
        if (host.commentsBox == null) return;
        host.commentsBox.getChildren().clear();

        for (Map.Entry<String, Comment> entry : comments.entrySet()) {
            Comment comment = entry.getValue();

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
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

}
