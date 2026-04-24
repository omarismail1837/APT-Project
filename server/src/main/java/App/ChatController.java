package App;

import App.crdt.action.Action;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class ChatController {
    private final SimpMessagingTemplate messagingTemplate;
    private final ActionRepository actionRepository; // Use the repository instead

    public ChatController(SimpMessagingTemplate messagingTemplate, ActionRepository actionRepository) {
        this.messagingTemplate = messagingTemplate;
        this.actionRepository = actionRepository;
    }

    @MessageMapping("/docs/{documentId}/send-data")
    public void sendUpdate(@DestinationVariable String documentId, Action update) {
        System.out.println("Recieved action: " + documentId);
        actionRepository.save(update); // save - automatically converted to json
        messagingTemplate.convertAndSend("/topic/docs/" + documentId + "/updates", update); //broadcast
    }

    @SubscribeMapping("/docs/{docId}/initial-state")
    public List<Action> initialState(@DestinationVariable String docId) {
        System.out.println("User subscribed to " + docId + ". Sending full history...");

        List<Action> history = actionRepository.findByDocumentId(docId);

        return history;
    }
}
