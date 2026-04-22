package App;

import App.crdt.action.Action;
import App.crdt.block.BlockDLL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ChatController {

    // @Autowired
    private final BlockDLL document;
    private final Map<String, Map<Integer, Integer>> docColorAssignments = new LinkedHashMap<>();

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    public ChatController(BlockDLL document) {
        this.document = document;
    }

    private int assignColorIndex(String documentID, int siteID) {
        Map<Integer, Integer> colorsForDoc =
                docColorAssignments.computeIfAbsent(documentID, id -> new LinkedHashMap<>());

        Integer existing = colorsForDoc.get(siteID);
        if (existing != null) {
            return existing;
        }

        for (int i = 0; i < 4; i++) {
            if (!colorsForDoc.containsValue(i)) {
                colorsForDoc.put(siteID, i);
                return i;
            }
        }

        int fallback = Math.abs(siteID) % 4;
        colorsForDoc.put(siteID, fallback);
        return fallback;
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

        update.setColorIndex(assignColorIndex(update.getDocumentID(), update.getSiteID()));

        String type = update.getActionType();
        if (type != null && !type.equals("CURSOR")) {
            document.applyAction(update);
        }

//        document.applyAction(update);
        return update;
    }

    @SubscribeMapping("/initial-state")
    public void sendInitialState(org.springframework.messaging.simp.stomp.StompHeaderAccessor headerAccessor) {
        List<Action> snapshot = document.getAllActions();
        System.out.println("[SERVER] Initial-state size=" + snapshot.size());

        // Send in batches of 100 to avoid oversized payloads
        int batchSize = 100;
        for (int i = 0; i < snapshot.size(); i += batchSize) {
            int end = Math.min(i + batchSize, snapshot.size());
            List<Action> batch = snapshot.subList(i, end);

            // Send each batch as a separate message
            if (messagingTemplate != null) {
                String sessionId = headerAccessor.getSessionId();
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/initial-state-batch", batch);
                System.out.println("[SERVER] Sent initial-state batch " + (i/batchSize + 1) + ": size=" + batch.size());
            }
        }

        // Send a final empty message to signal completion
        if (messagingTemplate != null) {
            String sessionId = headerAccessor.getSessionId();
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/initial-state-done", "DONE");
            System.out.println("[SERVER] Sent initial-state completion signal");
        }
    }
}
