package App;

import App.crdt.block.BlockDLL;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class HelloController {
    private final BlockDLL blockDLL;

    // recv the document from HelloApplication's controller factory
    public HelloController(BlockDLL blockDLL) {
        this.blockDLL = blockDLL;
    }

    @FXML
    private Button newDocButton;

    @FXML
    private Button joinButton;

    @FXML
    private TextField sessionCodeField;

    @FXML
    // doesnt open new window; replaces the current scene with a new one loaded from new-doc.fxml
    private void newDoc() throws IOException {
        Stage stage = (Stage) newDocButton.getScene().getWindow();
        URL fxmlUrl = resolveFxml("/App/new-doc.fxml");
        if (fxmlUrl == null) {
            throw new IllegalStateException("Cannot find FXML: /App/new-doc.fxml");
        }

        FXMLLoader loader = HelloApplication.createLoader(fxmlUrl);
        Parent root = loader.load();
        Scene scene = new Scene(root);
        URL cssUrl = HelloApplication.class.getResource("/App/editor.css");
        if (cssUrl == null) {
            System.out.println("CSS NOT FOUND");
        } else {
            System.out.println("CSS found: " + cssUrl);
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    @FXML
    private void joinSession() throws IOException {
        // Use the specific ID logic you already have
        String sessionID = sessionCodeField.getText();
        loadEditorScene(sessionID, joinButton);
    }

    /**
     * Helper method to prevent code duplication and fix the double-scene bug.
     */
    private void loadEditorScene(String docID, Button sourceButton) throws IOException {
        URL fxmlUrl = resolveFxml("/App/new-doc.fxml");
        if (fxmlUrl == null) {
            throw new IllegalStateException("Cannot find FXML: /App/new-doc.fxml");
        }

        FXMLLoader loader = HelloApplication.createLoader(fxmlUrl);

        // 1. Setup the Factory to pass data to BlankController
        loader.setControllerFactory(type -> {
            if (type == BlankController.class) {
                return new BlankController(docID, this.blockDLL);
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create controller", e);
            }
        });

        // 2. Load the root only ONCE
        Parent root = loader.load();
        Scene scene = new Scene(root);

        // 3. Apply CSS to THIS specific scene instance
        URL cssUrl = HelloApplication.class.getResource("/App/editor.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        } else {
            System.err.println("WARNING: editor.css not found at /App/editor.css");
        }

        // 4. Update the Stage
        Stage stage = (Stage) sourceButton.getScene().getWindow();
        stage.setScene(scene);
        stage.show();
    }

    // same thing as in HelloApplication but for new-doc.fxml instead of hello-view.fxml
    private URL resolveFxml(String classpathLocation) throws IOException {
        URL fromClasspath = HelloApplication.class.getResource(classpathLocation);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        Path fallback = Paths.get("Client", "src", "main", "resources", "App", "new-doc.fxml");
        if (Files.exists(fallback)) {
            return fallback.toUri().toURL();
        }
        return null;
    }
}
