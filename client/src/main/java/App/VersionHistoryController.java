package App;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class VersionHistoryController {

    private static final String BASE_URL = "https://apt-project-production-326d.up.railway.app";
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("MMM d, yyyy  HH:mm");

    private final BlankController host;
    private final EditorDocumentController docController;

    public VersionHistoryController(BlankController host, EditorDocumentController docController) {
        this.host = host;
        this.docController = docController;
    }

    public void saveVersion() {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle("Save Version");
        dlg.setHeaderText(null);
        dlg.setContentText("Version label (optional):");
        if (host.textArea != null && host.textArea.getScene() != null)
            dlg.initOwner(host.textArea.getScene().getWindow());

        dlg.showAndWait().ifPresent(label -> {
            String currentText = host.textArea != null ? host.textArea.getText() : "";
            Thread t = new Thread(() -> {
                String result = postSaveVersion(host.getDocID(), label.trim(), host.getUsername(), currentText);
                Platform.runLater(() -> {
                    if (result != null) showInfo("Version saved", "The version was saved successfully.");
                    else showError("Save failed", "Could not save the version. Check your connection.");
                });
            }, "save-version");
            t.setDaemon(true);
            t.start();
        });
    }

    public void showHistory() {
        Window owner = host.textArea != null && host.textArea.getScene() != null
                ? host.textArea.getScene().getWindow() : null;
        Thread t = new Thread(() -> {
            String json = fetchVersionList(host.getDocID());
            Platform.runLater(() -> openHistoryDialog(json, owner));
        }, "fetch-versions");
        t.setDaemon(true);
        t.start();
    }

    private void openHistoryDialog(String json, Window owner) {
        if (json == null) { showError("Error", "Could not load version history."); return; }

        JSONArray versions;
        try { versions = new JSONArray(json); }
        catch (Exception e) { showError("Error", "Invalid response from server."); return; }

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Version History — " + host.getDocName());

        ListView<JSONObject> listView = new ListView<>();
        listView.setPrefWidth(200);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(JSONObject item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.optString("label", "—") + "\n" + item.optString("createdBy", ""));
                setStyle("-fx-font-size: 12px;");
            }
        });
        for (int i = 0; i < versions.length(); i++) listView.getItems().add(versions.getJSONObject(i));

        Label dateLabel   = new Label(); dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #777;");
        Label authorLabel = new Label(); authorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #777;");
        Label actionsLabel = new Label(); actionsLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #777;");
        TextArea preview = new TextArea();
        preview.setEditable(false); preview.setWrapText(true); preview.setPrefHeight(250);

        Button restoreBtn = new Button("Restore this version");
        restoreBtn.setDisable(true);
        restoreBtn.setStyle("-fx-background-color: #8b5cf6; -fx-text-fill: white; -fx-padding: 6 16;");

        final JSONObject[] selected = {null};
        listView.getSelectionModel().selectedItemProperty().addListener((obs, old, v) -> {
            selected[0] = v;
            if (v == null) { dateLabel.setText(""); authorLabel.setText(""); actionsLabel.setText(""); preview.setText(""); restoreBtn.setDisable(true); return; }
            long ms = v.optLong("createdAt", 0);
            dateLabel.setText("Saved: " + (ms > 0 ? DATE_FMT.format(new Date(ms)) : "—"));
            authorLabel.setText("By: " + v.optString("createdBy", "Unknown"));
            actionsLabel.setText("Actions in snapshot: " + v.optInt("actionCount", 0));
            preview.setText(v.optString("contentPreview", "(empty)"));
            restoreBtn.setDisable(false);
        });

        restoreBtn.setOnAction(e -> {
            if (selected[0] == null) return;
            String vId = selected[0].optString("id");
            String lbl = selected[0].optString("label", "this version");
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Restore Version");
            confirm.setHeaderText("Restore \"" + lbl + "\"?");
            confirm.setContentText("All changes after this version will be lost for everyone. This cannot be undone.");
            confirm.initOwner(dialog);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.OK) {
                    dialog.close();
                    Thread t = new Thread(() -> {
                        boolean ok = postRestore(host.getDocID(), vId);
                        Platform.runLater(() -> {
                            if (!ok) {
                                showError("Restore failed", "The server could not restore the version.");
                                return;
                            }
                            host.handleRemoteRestore();
                        });
                    }, "restore-version");
                    t.setDaemon(true); t.start();
                }
            });
        });

        VBox rightPane = new VBox(8, dateLabel, authorLabel, actionsLabel, preview, restoreBtn);
        rightPane.setPadding(new Insets(10)); rightPane.setPrefWidth(300);
        VBox.setVgrow(preview, Priority.ALWAYS);
        HBox content = new HBox(listView, rightPane);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        if (versions.length() == 0) {
            Label empty = new Label("No saved versions yet.\nUse \"Save Version\" in the editor toolbar.");
            empty.setStyle("-fx-text-fill: #999; -fx-font-size: 13px;"); empty.setWrapText(true);
            VBox box = new VBox(empty); box.setPadding(new Insets(20));
            dialog.setScene(new Scene(box, 360, 180));
        } else {
            dialog.setScene(new Scene(content, 520, 440));
        }

        dialog.show();
        if (!listView.getItems().isEmpty()) listView.getSelectionModel().selectFirst();
    }

    private String postSaveVersion(String docId, String label, String username, String content) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/docs/" + docId + "/versions").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            JSONObject body = new JSONObject();
            body.put("label", label == null || label.isBlank() ? "" : label);
            body.put("createdBy", username == null ? "Anonymous" : username);
            body.put("content", content == null ? "" : content);
            try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            if (conn.getResponseCode() == 200) return readResponse(conn);
        } catch (IOException ex) { System.err.println("saveVersion error: " + ex.getMessage()); }
        return null;
    }

    private String fetchVersionList(String docId) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/docs/" + docId + "/versions").openConnection();
            if (conn.getResponseCode() == 200) return readResponse(conn);
        } catch (IOException ex) { System.err.println("fetchVersionList error: " + ex.getMessage()); }
        return null;
    }

    private boolean postRestore(String docId, String versionId) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/docs/" + docId + "/versions/" + versionId + "/restore").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Length", "0");
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            return conn.getResponseCode() == 200;
        } catch (IOException ex) { System.err.println("postRestore error: " + ex.getMessage()); return false; }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString().trim();
        }
    }

    private void showInfo(String title, String msg) { Alert a = new Alert(Alert.AlertType.INFORMATION); a.setTitle(title); a.setHeaderText(null); a.setContentText(msg); a.showAndWait(); }
    private void showError(String title, String msg) { Alert a = new Alert(Alert.AlertType.ERROR); a.setTitle(title); a.setHeaderText(null); a.setContentText(msg); a.showAndWait(); }
}