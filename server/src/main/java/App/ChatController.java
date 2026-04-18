package App;

import App.crdt.action.Action;
import App.crdt.block.BlockDLL;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class ChatController {

    // @Autowired
    private final BlockDLL document;

    public ChatController(BlockDLL document) {
        this.document = document;
    }

    @MessageMapping("/send-data")
    @SendTo("/topic/updates")
    public Action sendUpdate(Action update) {
        // Apply to server-side CRDT to keep the master copy updated
        document.applyAction(update);
        // myCrdtEngine.apply(update);
        return update;
    }

    @SubscribeMapping("/initial-state")
    public List<Action> sendInitialState() {
        System.out.println("New user joined. Sending full document state...");
        // Return the full list of actions
        return document.getAllActions();
    }
}