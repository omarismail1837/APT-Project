package App;

import App.crdt.block.BlockDLL;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public class HelloController {
    private final BlockDLL blockDLL;

    @FXML private Button newDocButton;
    @FXML private Button joinButton;
    @FXML private Button importButton;
    @FXML private TextField sessionCodeField;
    @FXML private TextField newDocNameField;
    @FXML private TextField signupNameField;
    @FXML private TextField signupUsernameField;
    @FXML private TextField loginUsernameField;
    @FXML private PasswordField signupPasswordField;
    @FXML private PasswordField signupConfirmPasswordField;
    @FXML private PasswordField loginPasswordField;
    @FXML private VBox sessionPane;
    @FXML private VBox authActionsPane;
    @FXML private VBox signupPane;
    @FXML private VBox loginPane;
    @FXML private VBox browsePane;
    @FXML private Button browseDocsButton;
    @FXML private Label welcomeTitleLabel;
    @FXML private Label accountStatusLabel;
    @FXML private Label loggedInLabel;
    @FXML private Label signupStatusLabel;
    @FXML private Label loginStatusLabel;
    @FXML private FlowPane docsFlowPane;

    private int userId;
    private String username;
    private String accountId;

    public HelloController(BlockDLL blockDLL) {
        this.blockDLL = blockDLL;
        userId = String.valueOf(Math.random() * 10000).hashCode();
    }

    @FXML
    public void initialize() {
        updateSessionAccountUi();
        showSessionPane();
    }

    @FXML
    private void newDoc() {
        try {
            String nameInput = null;
            if (newDocNameField != null) {
                nameInput = newDocNameField.getText();
            }
            if (nameInput == null || nameInput.isBlank()) {
                nameInput = "New Document";
            }
            // 1. Create a unique ID for the document
            String docId = UUID.randomUUID().toString();

            // 2. Register it on the server and get the generated codes
            SessionInfo sessionInfo = getCodesFromServer(docId, nameInput);

            String joinUrl = "https://apt-project-production-326d.up.railway.app/join?code=" +
                    sessionInfo.editCode + "&userId=" + userId;

            HttpURLConnection conn = (HttpURLConnection) new URL(joinUrl).openConnection();

            // 4. Process the session (this will extract the role and move to the editor)
            processSession(conn, newDocButton, nameInput, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void joinSession() {
        try {
            String inputCode = sessionCodeField.getText();
            if (inputCode == null || inputCode.isBlank()) return;

            String urlString = "https://apt-project-production-326d.up.railway.app/join?code=" +
                    inputCode + "&userId=" + userId;

            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();

            // Use the same processSession method for joining (no provided doc name)
            processSession(conn, joinButton, null, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processSession(HttpURLConnection conn, Button sourceButton,
                                String providedDocName, String importContent) throws Exception {
        if (conn.getResponseCode() == 200) {
            String raw = readResponse(conn).trim();
            if (raw.equals("invalid")) {
                invalidCodeAlert();
                return;
            }

            // role:docId:docName:editCode:viewCode
            String[] parts = raw.split(":");
            String role = parts[0];
            String actualDocId = parts[1];
            String docName = parts[2];
            String serverEditCode = parts[3];
            String viewCode = parts[4];

            boolean canEdit = role.equalsIgnoreCase("editor");
            String finalEditCode = "null".equals(serverEditCode) ? null : serverEditCode;

            // Call loader with the correct order of arguments
            loadEditorScene(actualDocId, docName, viewCode, finalEditCode, canEdit, userId, sourceButton, importContent);
        }
    }

    private void invalidCodeAlert()
    {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Invalid Code");
        alert.setHeaderText(null);
        alert.setContentText("The session code you entered is invalid. Please check and try again.");
        alert.showAndWait();
    }

    private void loadEditorScene(String docID, String docName, String viewCode, String editCode,
                                 boolean canEdit, int userId, Button sourceButton,
                                 String importContent) throws IOException {
        // Ensure you use the correct FXML name (blank-view.fxml or new-doc.fxml)
        URL fxmlUrl = resolveFxml("/App/blank-view.fxml");
        if (fxmlUrl == null) fxmlUrl = resolveFxml("/App/new-doc.fxml");

        FXMLLoader loader = new FXMLLoader(fxmlUrl);

        // This is the key: we manually tell the loader how to build BlankController
        loader.setControllerFactory(type -> {
            if (type == BlankController.class) {
                return new BlankController(docID, docName, viewCode, editCode, userId, this.blockDLL, canEdit, username);
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load controller", e);
            }
        });

        Parent root = loader.load();
        Object controller = loader.getController();

        // after loading, add importContent if not null
        if (importContent != null && controller instanceof BlankController)
        {
            BlankController bc = (BlankController) controller;
            javafx.application.Platform.runLater(() -> bc.insertImportedText(importContent));
        }
        Scene scene = new Scene(root);

        URL cssUrl = HelloApplication.class.getResource("/App/editor.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

        Stage stage = (Stage) sourceButton.getScene().getWindow();

        // Make sure the BlankController gets a chance to cleanup (disconnect websocket)
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

    private SessionInfo getCodesFromServer(String docId, String name) throws IOException {
        String encodedName = URLEncoder.encode(name == null ? "" : name, StandardCharsets.UTF_8);
        URL url = new URL("https://apt-project-production-326d.up.railway.app/docs/" + docId + "/get-codes" +
                "?userId=" + accountId +
                "&name=" + encodedName);

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


    @FXML
    private void showSignup() {
        clearStatusLabels();
        setVisiblePane(signupPane);
    }

    @FXML
    private void showLogin() {
        clearStatusLabels();
        setVisiblePane(loginPane);
    }

    @FXML
    private void showBrowse() {
        if (accountId == null) return;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://apt-project-production-326d.up.railway.app/docs/" + accountId).openConnection();
            String response = readResponse(conn);
            populateDocsPane(response);
        } catch (IOException e) {
            e.printStackTrace();
        }
        clearStatusLabels();
        setVisiblePane(browsePane);
    }

    private void populateDocsPane(String response) {
        docsFlowPane.getChildren().clear();
        JSONArray arr = new JSONArray(response);

        // fallback for when user doesnt have any docs
        if (arr.isEmpty()) {
            Label empty = new Label("You don't have any documents yet.\nCreate or import one to get started.");
            empty.setStyle("-fx-text-fill: #888; -fx-font-size: 14px; -fx-text-alignment: center;");
            empty.setWrapText(true);
            docsFlowPane.getChildren().add(empty);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject doc = arr.getJSONObject(i);
            String docId = doc.getString("docId");
            String name = doc.getString("name");
            String editCode = doc.optString("editCode", null);
            docsFlowPane.getChildren().add(makeDocCard(docId, name, editCode));
        }
    }

    private VBox makeDocCard(String docId, String name, String editCode) {
        // preview area
        Label preview = new Label("Click to open");
        preview.setWrapText(true);
        preview.setStyle("-fx-text-fill: #b4b2a9; -fx-font-size: 12px;");

        VBox previewBox = new VBox(preview);
        previewBox.setPrefSize(140, 90);
        previewBox.setStyle("-fx-background-color: #f8f7f4; -fx-background-radius: 6; -fx-padding: 8;");

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1a1a1a;");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(140);

        Button deleteButton = new Button("Delete");
        deleteButton.getStyleClass().add("delete-doc-button");
        deleteButton.setMaxWidth(Double.MAX_VALUE);

        deleteButton.setOnAction(e -> {
            e.consume();
            deleteOwnedDocument(docId, name);
        });

        VBox openCard = new VBox(8, previewBox, nameLabel);
        openCard.getStyleClass().add("doc-open-card");
        openCard.setPrefWidth(156);

        openCard.setOnMouseClicked(e -> {
            if (editCode == null || editCode.isBlank()) return;
            try {
                String joinUrl = "https://apt-project-production-326d.up.railway.app/join?code="
                        + editCode + "&userId=" + userId;
                HttpURLConnection conn = (HttpURLConnection) new URL(joinUrl).openConnection();
                processSession(conn, newDocButton, name, null);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        VBox card = new VBox(8, openCard, deleteButton);
        card.setPrefWidth(156);
        return card;
    }

    private void deleteOwnedDocument(String docId, String name) {
        if (docId == null || docId.isBlank() || accountId == null || accountId.isBlank()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete document");
        confirm.setHeaderText("Delete \"" + name + "\"?");
        confirm.setContentText("This permanently removes the document and its saved history.");

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        try {
            String encodedAccountId = URLEncoder.encode(accountId, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://apt-project-production-326d.up.railway.app/docs/" + docId
                            + "?accountId=" + encodedAccountId).openConnection();
            conn.setRequestMethod("DELETE");

            int responseCode = conn.getResponseCode();
            String body = readResponse(conn);

            if (responseCode == 200) {
                showBrowse(); // refresh the list
            } else if (responseCode == 403) {
                showImportAlert(Alert.AlertType.ERROR, "Not allowed",
                        "You don't own this document and cannot delete it.");
            } else if (responseCode == 400 && "not_found".equals(body)) {
                showImportAlert(Alert.AlertType.ERROR, "Not found",
                        "Document no longer exists. Refreshing.");
                showBrowse();
            } else {
                showImportAlert(Alert.AlertType.ERROR, "Delete failed",
                        "Server returned HTTP " + responseCode + ".");
            }
            conn.disconnect();
        } catch (IOException ex) {
            showImportAlert(Alert.AlertType.ERROR, "Delete failed",
                    "Could not reach the server.");
        }
    }

    @FXML
    private void showSessionPane() {
        clearStatusLabels();
        updateSessionAccountUi();
        setVisiblePane(sessionPane);
    }

    @FXML
    private void createAccount() {
        String name = signupNameField != null ? signupNameField.getText().trim() : "";
        String username = signupUsernameField != null ? signupUsernameField.getText().trim() : "";
        String password = signupPasswordField != null ? signupPasswordField.getText() : "";
        String confirm = signupConfirmPasswordField != null ? signupConfirmPasswordField.getText() : "";

        if (name.isBlank() || username.isBlank() || password.isBlank() || confirm.isBlank()) {
            setSignupStatus("Fill in all fields.");
            return;
        }

        if (password.length() < 8) {
            setSignupStatus("Use at least 8 characters for the password.");
            return;
        }

        if (!password.equals(confirm)) {
            setSignupStatus("Passwords do not match.");
            return;
        }

        String res;
        try {
            res = sendAuthRequest("signup", username, password);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (res.equals("username_not_unique")) {
            setSignupStatus("Username is taken. Please choose another username.");
            return;
        }

        userId = res.hashCode();
        accountId = res;
        this.username = username;
        returnToSessionToolsAfterAuth();
    }

    @FXML
    private void loginAccount() {
        String username = loginUsernameField != null ? loginUsernameField.getText().trim() : "";
        String password = loginPasswordField != null ? loginPasswordField.getText() : "";

        if (username.isBlank() || password.isBlank()) {
            setLoginStatus("Enter your username and password.");
            return;
        }

        String res;
        try {
            res = sendAuthRequest("login", username, password);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (res.equals("does_not_exist"))
        {
            setLoginStatus("No account found with that username.");
            return;
        }
        else if (res.equals("incorrect_password"))
        {
            setLoginStatus("Incorrect password. Try again.");
            return;
        }

        // returned user id is string but everything else is int based, so we hash it to get an int
        userId = res.hashCode();
        accountId = res;
        this.username = username;
        returnToSessionToolsAfterAuth();
    }

    private String sendAuthRequest(String endpoint, String username, String password) throws IOException {
        // for better security im not adding password in the url itself
        URL url = new URL("https://apt-project-production-326d.up.railway.app/" + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json"); // send a json
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);

        // write the json body to the output stream
        conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        return readResponse(conn);
    }

    @FXML
    private void importDoc()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import document");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));

        java.io.File file = chooser.showOpenDialog(null); // opens the picker
        if (file == null) return; // user cancelled

        String content;
        try {
            content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            showImportAlert(Alert.AlertType.ERROR, "Import failed", "Could not read the text file.");
            return;
        }

        String filename = file.getName();
        newDocFromImport(filename, content);
    }

    private void showImportAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // same as newdoc except name is given
    private void newDocFromImport(String fname, String content) {
        try {
            // 1. Create a unique ID for the document
            String docId = UUID.randomUUID().toString();

            // 2. Register it on the server and get the generated codes
            SessionInfo sessionInfo = getCodesFromServer(docId, fname);

            String joinUrl = "https://apt-project-production-326d.up.railway.app/join?code=" +
                    sessionInfo.editCode + "&userId=" + userId;

            HttpURLConnection conn = (HttpURLConnection) new URL(joinUrl).openConnection();

            // 4. Process the session (this will extract the role and move to the editor)
            processSession(conn, newDocButton, fname, content);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void setSignupStatus(String message) {
        if (signupStatusLabel != null) {
            signupStatusLabel.setText(message);
        }
    }

    private void setLoginStatus(String message) {
        if (loginStatusLabel != null) {
            loginStatusLabel.setText(message);
        }
    }

    private void clearStatusLabels() {
        if (signupStatusLabel != null) signupStatusLabel.setText("");
        if (loginStatusLabel != null) loginStatusLabel.setText("");
    }

    private void setVisiblePane(VBox targetPane) {
        setPaneVisible(sessionPane, targetPane == sessionPane);
        setPaneVisible(signupPane, targetPane == signupPane);
        setPaneVisible(loginPane, targetPane == loginPane);
        setPaneVisible(browsePane, targetPane == browsePane);
    }

    private void setPaneVisible(VBox pane, boolean visible) {
        if (pane == null) return;
        pane.setVisible(visible);
        pane.setManaged(visible);
    }

    private void returnToSessionToolsAfterAuth() {
        if (docsFlowPane != null) {
            docsFlowPane.getChildren().clear();
        }
        clearStatusLabels();
        updateSessionAccountUi();
        setVisiblePane(sessionPane);

        if (newDocNameField != null) {
            javafx.application.Platform.runLater(() -> newDocNameField.requestFocus());
        }
    }

    private void updateSessionAccountUi() {
        boolean isLoggedIn = accountId != null && !accountId.isBlank();
        String displayUsername = username == null || username.isBlank() ? "user" : username;

        if (welcomeTitleLabel != null) {
            welcomeTitleLabel.setText(isLoggedIn ? "Welcome, " + displayUsername : "Welcome");
        }

        if (accountStatusLabel != null) {
            accountStatusLabel.setText(isLoggedIn
                    ? "Start writing, browse your documents, or join an existing session."
                    : "You're using anonymous mode. You can create or join sessions, but saved documents need an account.");
        }

        if (browseDocsButton != null) {
            browseDocsButton.setVisible(isLoggedIn);
            browseDocsButton.setManaged(isLoggedIn);
        }

        if (authActionsPane != null) {
            authActionsPane.setVisible(!isLoggedIn);
            authActionsPane.setManaged(!isLoggedIn);
        }

        if (loggedInLabel != null) {
            loggedInLabel.setText(isLoggedIn ? "Logged in as " + displayUsername + "." : "");
            loggedInLabel.setVisible(isLoggedIn);
            loggedInLabel.setManaged(isLoggedIn);
        }
    }
}
