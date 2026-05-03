package App;

import App.crdt.action.Action;
import App.crdt.character.CharNode;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static io.micrometer.common.util.StringUtils.truncate;

public class CommentController {
    private final BlankController host;
    private final java.util.Map<String, String> comments = new LinkedHashMap<>();

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
        Label preview = new Label("\"" + truncate(selectedText, 60) + "\"");
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
                comments.put(key, text);
                // highlight the annotated range
                for (int i = start; i < end; i++) {
                    System.out.println("highlighting index " + i);
                    CharNode node = host.getDocumentController().getVisibleNode(i);
                    if (node != null) {
                        System.out.println("the node is null!");
                        node.setHighlighted(true);
                    }
                }
                host.getDocumentController().rerender(host.textArea.getCaretPosition(), host.textArea.getAnchor());
            }
            dialog.close();
            // refresh sidebar here inshallah
        });

        cancelBtn.setOnAction(ev -> dialog.close());

        dialog.setScene(new Scene(layout));
        dialog.showAndWait();
    }
}
