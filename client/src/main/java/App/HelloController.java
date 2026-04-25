package App;

import App.crdt.block.BlockDLL;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public class HelloController {
    private final BlockDLL blockDLL;

    @FXML private Button newDocButton;
    @FXML private Button joinButton;
    @FXML private TextField sessionCodeField;

    private String userId;

    public HelloController(BlockDLL blockDLL) {
        this.blockDLL = blockDLL;
        userId = String.valueOf(Math.random() * 10000);
    }

    @FXML
    public void initialize() {
        // UI is ready here
    }

    @FXML
    private void newDoc() {
        try {
            // 1. Create a unique ID for the document
            String docId = UUID.randomUUID().toString();

            // 2. Register it on the server and get the generated codes
            SessionInfo sessionInfo = getCodesFromServer(docId);

            // 3. Use the Global Join to enter as the host/editor
            String numericId = String.valueOf(Math.abs(userId.hashCode()));
            String joinUrl = "https://apt-project-production-326d.up.railway.app/join?code=" +
                    sessionInfo.editCode + "&userId=" + numericId;

            HttpURLConnection conn = (HttpURLConnection) new URL(joinUrl).openConnection();

            // 4. Process the session (this will extract the role and move to the editor)
            processSession(conn, newDocButton);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void joinSession() {
        try {
            String inputCode = sessionCodeField.getText();
            if (inputCode == null || inputCode.isBlank()) return;

            String numericId = String.valueOf(Math.abs(userId.hashCode()));
            String urlString = "https://apt-project-production-326d.up.railway.app/join?code=" +
                    inputCode + "&userId=" + numericId;

            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();

            // Use the same processSession method for joining
            processSession(conn, joinButton);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processSession(HttpURLConnection conn, Button sourceButton) throws Exception {
        if (conn.getResponseCode() == 200) {
            String raw = readResponse(conn).trim();
            if (raw.equals("invalid")) {
                System.err.println("❌ Access Denied: Invalid code.");
                return;
            }

            // The server returns: role:docId:editCode:viewCode
            String[] parts = raw.split(":");
            String role = parts[0];
            String actualDocId = parts[1];
            String serverEditCode = parts[2];
            String viewCode = parts[3];

            boolean canEdit = role.equalsIgnoreCase("editor");
            String finalEditCode = "null".equals(serverEditCode) ? null : serverEditCode;

            // Call loader with the correct order of arguments
            loadEditorScene(actualDocId, viewCode, finalEditCode, canEdit, userId, sourceButton);
        }
    }

    private void loadEditorScene(String docID, String viewCode, String editCode, boolean canEdit, String userId, Button sourceButton) throws IOException {
        // Ensure you use the correct FXML name (blank-view.fxml or new-doc.fxml)
        URL fxmlUrl = resolveFxml("/App/blank-view.fxml");
        if (fxmlUrl == null) fxmlUrl = resolveFxml("/App/new-doc.fxml");

        FXMLLoader loader = new FXMLLoader(fxmlUrl);

        // This is the key: we manually tell the loader how to build BlankController
        loader.setControllerFactory(type -> {
            if (type == BlankController.class) {
                int siteID = Math.abs(userId.hashCode());
                return new BlankController(docID, viewCode, editCode, siteID, this.blockDLL, canEdit);
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load controller", e);
            }
        });

        Parent root = loader.load();
        Scene scene = new Scene(root);

        URL cssUrl = HelloApplication.class.getResource("/App/editor.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

        Stage stage = (Stage) sourceButton.getScene().getWindow();

        // Make sure the BlankController gets a chance to cleanup (disconnect websocket)
        Object controller = loader.getController();
        if (controller instanceof BlankController) {
            BlankController bc = (BlankController) controller;
            stage.setOnCloseRequest(evt -> {
                try {
                    bc.close();
                } catch (Exception ex) {
                    System.err.println("Error during stage close: " + ex.getMessage());
                }
            });
        }

        stage.setScene(scene);
        stage.show();
    }

    private SessionInfo getCodesFromServer(String docId) throws IOException {
        URL url = new URL("https://apt-project-production-326d.up.railway.app/docs/" + docId + "/get-codes");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        if (conn.getResponseCode() != 200) {
            throw new IOException("Server error getting codes: HTTP " + conn.getResponseCode());
        }

        JSONObject json = new JSONObject(readResponse(conn));
        return new SessionInfo(
                docId,
                json.getString("editCode"),
                json.getString("viewCode")
        );
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private boolean callEndpoint(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        return conn.getResponseCode() == 200;
    }

    private static class SessionInfo {
        String docId, editCode, viewCode;
        SessionInfo(String d, String e, String v) {
            this.docId = d; this.editCode = e; this.viewCode = v;
        }
    }

    private URL resolveFxml(String loc) {
        URL res = HelloApplication.class.getResource(loc);
        if (res != null) return res;

        // FIX: Strip the leading slash so Paths.get treats it as a relative path
        String cleanLoc = loc.startsWith("/") ? loc.substring(1) : loc;
        Path fallback = Paths.get("Client", "src", "main", "resources", cleanLoc);

        try {
            return Files.exists(fallback) ? fallback.toUri().toURL() : null;
        } catch (Exception e) {
            return null;
        }
    }
}