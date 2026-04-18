package App;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        URL fxmlUrl = resolveFxml("/App/hello-view.fxml");
        if (fxmlUrl == null) {
            throw new IllegalStateException("Cannot find FXML: /App/hello-view.fxml. " +
                    "If running from IntelliJ Main, set run module/classpath to Client module or enable resources output.");
        }

        FXMLLoader fxmlLoader = new FXMLLoader(fxmlUrl);
        Scene scene = new Scene(fxmlLoader.load(), 1000, 500);
        stage.setTitle("Hello!");
        stage.setScene(scene);
        stage.show();
    }

    private URL resolveFxml(String classpathLocation) throws IOException {
        URL fromClasspath = HelloApplication.class.getResource(classpathLocation);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        // Fallback for IDE runs where resources are not copied to classpath output.
        Path fallback = Paths.get("Client", "src", "main", "resources", "App", "hello-view.fxml");
        if (Files.exists(fallback)) {
            return fallback.toUri().toURL();
        }

        return null;
    }
}
