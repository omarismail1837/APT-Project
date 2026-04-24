package App;

import App.crdt.action.Action;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Controller
public class ChatController {

    private final HashMap<String, List<Action>> activeDocuments = new HashMap<>();

    public ChatController() {allActions = new ArrayList<>();}

    @MessageMapping("/send-data")
    @SendTo("/topic/updates")
    public Action sendUpdate(Action update) {
        System.out.println("Received update: " + update);
        if (!allActions.contains(update) && !update.getActionType().equals("CURSOR")) allActions.add(update);
        return update;
    }

    @SubscribeMapping("/initial-state")
    public List<Action> sendInitialState() {
        System.out.println("New user joined. Sending full document state...");
        // Return the full list of actions
        return new ArrayList<>(allActions);
    }
}