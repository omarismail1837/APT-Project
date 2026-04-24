package App;

import App.crdt.action.Action;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class ChatController {

    private final ConcurrentHashMap<String, List<Action>> activeDocs = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/docs/{docId}/send-data")
    public void sendUpdate(@DestinationVariable String docId, Action update) {
        List<Action> state = activeDocs.computeIfAbsent(docId, id -> new ArrayList<Action>());
        state.add(update); // merge/apply in doc-local critical section
        messagingTemplate.convertAndSend("/topic/docs/" + docId + "/updates", update);
    }

    @SubscribeMapping("/docs/{docId}/initial-state")
    public List<Action> initialState(@DestinationVariable String docId) {
        return new ArrayList<>(activeDocs.computeIfAbsent(docId, id -> new ArrayList<>()));
    }
}