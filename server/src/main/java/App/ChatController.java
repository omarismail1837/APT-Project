package App;

import App.crdt.action.Action;
import App.crdt.block.BlockDLL;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
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
    public List<Action> sendInitialState() {
        List<Action> snapshot = document.getAllActions();
        System.out.println("[SERVER] Initial-state size=" + snapshot.size());
        return snapshot;
    }
}
