package App;

import App.crdt.action.Action;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

@Controller
public class ChatController {
    private final SimpMessagingTemplate messagingTemplate;
    private final ActionRepository actionRepository; // Use the repository instead

    //remove later
    private List<Action> allActions = new ArrayList<>();

    public ChatController(SimpMessagingTemplate messagingTemplate, ActionRepository actionRepository) {
        this.messagingTemplate = messagingTemplate;
        this.actionRepository = actionRepository;
    }

    @MessageMapping("/docs/{documentId}/send-data")
    public void sendUpdate(@DestinationVariable String documentId, Action update) {
        System.out.println("Recieved action: " + documentId);
        if(!update.getActionType().equals("CURSOR")) actionRepository.save(update); // save - automatically converted to json
        messagingTemplate.convertAndSend("/topic/docs/" + documentId + "/updates", update); //broadcast
    }

    @SubscribeMapping("/docs/{docId}/initial-state")
    public List<Action> initialState(@DestinationVariable String docId) {
        System.out.println("User subscribed to " + docId + ". Sending full history...");

        List<Action> history = actionRepository.findByDocumentId(docId);

        System.out.println("Sending " + history.size() + " actions");

        return history;
    }

    // code so that it works with version sumbitted -- remove later

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
