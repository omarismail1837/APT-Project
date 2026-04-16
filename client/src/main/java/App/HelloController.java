package App;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.scene.layout.*;
import javafx.scene.layout.*;

import java.io.IOException;

public class HelloController {
    @FXML
    private Button newDocButton;

    @FXML
    private void newDoc() throws IOException {
        Stage stage = (Stage) newDocButton.getScene().getWindow();
        Parent root = FXMLLoader.load(getClass().getResource("new-doc.fxml"));
        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.show();
    }
}
