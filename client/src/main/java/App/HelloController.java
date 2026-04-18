package App;

import App.crdt.block.BlockDLL;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;

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
