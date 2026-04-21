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
        if (update == null) {
            return null;
        }
        if (update.getDocumentID() == null || update.getDocumentID().isBlank()) {
            return null;
        }

        String type = update.getActionType();
        if (type != null && !type.equals("CURSOR")) {
            document.applyAction(update);
        }

//        document.applyAction(update);
        return update;
    }

    @SubscribeMapping("/initial-state")
    public List<Action> sendInitialState() {
        List<Action> snapshot = document.getAllActions();
        System.out.println("[SERVER] Initial-state size=" + snapshot.size());
        return snapshot;
    }
}