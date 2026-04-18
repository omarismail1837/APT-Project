package App;

import App.crdt.block.BlockDLL;
import javafx.application.Application;

public class ClientMain {
    public static void main(String[] args) {
        BlockDLL sharedDocument = new BlockDLL();
        HelloApplication.setSharedDocument(sharedDocument);
        Application.launch(HelloApplication.class, args);
    }
}
