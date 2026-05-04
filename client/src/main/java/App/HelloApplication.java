package App;

import App.crdt.block.BlockDLL;
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
    // One open document per application instance
    private static BlockDLL sharedDocument;

    public static void setSharedDocument(BlockDLL document) {
        sharedDocument = document;
    }

    public static BlockDLL getSharedDocument() {
        return sharedDocument;
    }

    // Override javafx's default way of creating controllers, we need to create controllers WITH the doc injected
    public static FXMLLoader createLoader(URL fxmlUrl) {
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        loader.setControllerFactory(type -> {
            if (type == HelloController.class) {
                return new HelloController(sharedDocument);
            }
            if (type == BlankController.class) {
                return new BlankController(sharedDocument);
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Unable to create controller: " + type.getName(), e);
            }
        });
        return loader;
    }

    @Override
    public void start(Stage stage) throws IOException {
        // if somehow ClientMain forgot to set the document, create one anyway so the app doesnt crash
        if (sharedDocument == null) {
            sharedDocument = new BlockDLL();
        }

        URL fxmlUrl = resolveFxml("/App/hello-view.fxml");
        if (fxmlUrl == null) {
            throw new IllegalStateException("Cannot find FXML: /App/hello-view.fxml. " +
                    "If running from IntelliJ Main, set run module/classpath to Client module or enable resources output.");
        }

        // build the ui components from the fxml
        FXMLLoader fxmlLoader = createLoader(fxmlUrl);

        // set window title, put the scene inside the window, then show it
        Scene scene = new Scene(fxmlLoader.load(), 1000, 500);
        stage.setTitle("Hello!");
        stage.setScene(scene);
        stage.show();
    }

    // in case IJ runs the class directly without going through mvn properly & the resources folder doesnt get copied,
    // look for the file directly on disk at its src loc
    private URL resolveFxml(String classpathLocation) throws IOException {
        // try the normal way first
        URL fromClasspath = HelloApplication.class.getResource(classpathLocation);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        // fallback for IDE runs where resources are not copied to classpath output.
        Path fallback = Paths.get("Client", "src", "main", "resources", "App", "hello-view.fxml");
        if (Files.exists(fallback)) {
            return fallback.toUri().toURL();
        }

        return null;
    }
}
