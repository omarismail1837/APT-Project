package App;

import App.crdt.action.Action;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Controller
public class ChatController {
    private final SimpMessagingTemplate messagingTemplate;
    private final ActionRepository actionRepository; // Use the repository instead
    // Session info: docId -> SessionInfo
    private final Map<String, SessionInfo> sessions = new HashMap<>();

    public ChatController(SimpMessagingTemplate messagingTemplate, ActionRepository actionRepository) {
        this.messagingTemplate = messagingTemplate;
        this.actionRepository = actionRepository;
    }

    // SessionInfo holds codes and lists of editors/viewers
    private static class SessionInfo {
        String editCode;
        String viewCode;
        List<String> editors = new ArrayList<>();
        List<String> viewers = new ArrayList<>();
        // Track userId -> last cursor Action
        Map<String, Action> cursors = new HashMap<>();
    }

    // Generate codes for a document session
    private SessionInfo getOrCreateSession(String docId) {
        return sessions.computeIfAbsent(docId, id -> {
            SessionInfo info = new SessionInfo();
            info.editCode = UUID.randomUUID().toString().substring(0, 8);
            info.viewCode = UUID.randomUUID().toString().substring(0, 8);
            return info;
        });
    }

    // Endpoint to get codes for a document
    @GetMapping("docs/{documentId}/get-codes")
    public Map<String, String> getCodes(@PathVariable String documentId) {
        SessionInfo info = getOrCreateSession(documentId);
        Map<String, String> codes = new HashMap<>();
        codes.put("editCode", info.editCode);
        codes.put("viewCode", info.viewCode);
        return codes;
    }

    // Endpoint to join a session (returns role) -- now requires documentId
    @GetMapping("/docs/{documentId}/join-session")
    public String joinSession(@PathVariable String documentId, @RequestParam String code, @RequestParam String userId) {
        SessionInfo info = sessions.get(documentId);
        if (info == null) return "invalid";
        String role = null;
        if (info.editCode.equals(code)) {
            if (!info.editors.contains(userId)) info.editors.add(userId);
            role = "editor";
        } else if (info.viewCode.equals(code)) {
            if (!info.viewers.contains(userId)) info.viewers.add(userId);
            role = "viewer";
        } else {
            return "invalid";
        }
        // Broadcast cursor add for this user
        Action cursorAction = new Action();
        cursorAction.setActionType("CURSOR");
        cursorAction.setSiteID(userId);
        cursorAction.setDocumentId(documentId);
        info.cursors.put(userId, cursorAction);
        messagingTemplate.convertAndSend("/topic/docs/" + documentId + "/updates", cursorAction);
        return role;
    }

    @MessageMapping("/docs/{documentId}/send-data")
    public void sendUpdate(@DestinationVariable String documentId, Action update) {
        String userId = String.valueOf(update.getSiteID());
        SessionInfo info = sessions.get(documentId);
        boolean canEdit = info != null && info.editors.contains(userId);
        System.out.println("Recieved action: " + documentId + ", userId=" + userId + ", canEdit=" + canEdit);
        if (update.getActionType().equals("CURSOR") && info != null && userId != null) {
            info.cursors.put(userId, update);
        }
        if (canEdit && !update.getActionType().equals("CURSOR")) {
            actionRepository.save(update);
        }
        if (canEdit || update.getActionType().equals("CURSOR")) {
            messagingTemplate.convertAndSend("/topic/docs/" + documentId + "/updates", update);
        }
    }

    @SubscribeMapping("/docs/{docId}/initial-state")
    public List<Action> initialState(@DestinationVariable String docId) {
        System.out.println("User subscribed to " + docId + ". Sending full history...");
        List<Action> history = actionRepository.findByDocumentId(docId);
        System.out.println("Sending " + history.size() + " actions");
        // Add all current cursors to the initial state
        SessionInfo info = sessions.get(docId);
        if (info != null && !info.cursors.isEmpty()) {
            history.addAll(info.cursors.values());
        }
        return history;
    }

    // Endpoint to leave a session (removes user from editors/viewers)
    @MessageMapping("/docs/{documentId}/leave-session")
    public String leaveSession(@DestinationVariable String documentId, Action action) {
        String userId = String.valueOf(action.getSiteID());
        String role = action.getExtraData(); // "editor" or "viewer"
        SessionInfo info = sessions.get(documentId);
        if (info == null || userId == null || role == null) return "invalid";
        boolean removed = false;
        if (role.equals("editor")) {
            removed = info.editors.remove(userId);
        } else if (role.equals("viewer")) {
            removed = info.viewers.remove(userId);
        }
        // Broadcast cursor remove for this user
        if (info.cursors.containsKey(userId)) {
            Action removeCursor = new Action();
            removeCursor.setActionType("CURSOR_REMOVE");
            removeCursor.setSiteID(action.getSiteID());
            removeCursor.setDocumentId(documentId);
            removeCursor.setExtraData(userId);
            messagingTemplate.convertAndSend("/topic/docs/" + documentId + "/updates", removeCursor);
            info.cursors.remove(userId);
        }
        if (info.editors.isEmpty() && info.viewers.isEmpty()) {
            sessions.remove(documentId);
        }
        return removed ? "left" : "not_found";
    }
}
