package App;

import App.crdt.block.BlockDLL;
import javafx.application.Application;

public class ClientMain {
    public static void main(String[] args) {
        // Actual document that will be edited
        BlockDLL sharedDocument = new BlockDLL();

        // Pass doc to HelloApplication before the window opens
        // ui will have access to the actual document data from the very start
        HelloApplication.setSharedDocument(sharedDocument);

        // launch javafx window
        // lanuch is a static function in Application
        Application.launch(HelloApplication.class, args);
    }
}
