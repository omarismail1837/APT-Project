package App;

import App.crdt.action.Action;
import net.datafaker.Faker;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;

@RestController
@Controller
public class ChatController {
    private final SimpMessagingTemplate messagingTemplate;
    private final ActionRepository actionRepository;
    private final DocRepository docRepository;
    private final UserRepository userRepository;
    private final DocVersionRepository docVersionRepository;
    private final Faker faker = new Faker();
    //needed to close session after 5 minutes of being empty
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    // Session info: docId -> SessionInfo
    private final Map<String, SessionInfo> sessions = new HashMap<>();

    public ChatController(SimpMessagingTemplate messagingTemplate, ActionRepository actionRepository, DocRepository docRepository, UserRepository userRepository,
                          DocVersionRepository docVersionRepository) {
        this.messagingTemplate = messagingTemplate;
        this.actionRepository = actionRepository;
        this.docRepository = docRepository;
        this.userRepository = userRepository;
        this.docVersionRepository = docVersionRepository;
    }
    private static class ContentSnapshot {
        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    // SessionInfo holds codes and lists of editors/viewers
    private static class SessionInfo {
        String editCode;
        String viewCode;
        List<String> editors = new ArrayList<>();
        List<String> viewers = new ArrayList<>();
        // Track userId -> last cursor Action
        Map<String, Action> cursors = new HashMap<>();
        Map<String, Action> presences = new HashMap<>();
    }

    private String generateReadableCode() {
        return String.format("%s-%s-%s-%d",
                // Use funnyName() as a substitute for adjectives
                faker.country().name().toLowerCase().replace(" ", ""),
                faker.color().name().toLowerCase().replace(" ", ""),
                faker.animal().name().toLowerCase().replace(" ", ""),
                faker.random().nextInt(10, 99)
        );
    }

    // Generate codes for a document session - creates document if doesnt exist
    private SessionInfo getOrCreateSession(String docId, String name, String ownerId) {
        // Check active sessions first
        if (sessions.containsKey(docId)) {
            return sessions.get(docId);
        }

        // check if docid already exists
        // We fetch the metadata to ensure the doc exists, but we generate FRESH codes.
        DocMetadata metadata = docRepository.findById(docId).orElseGet(() -> {
            // create if not in db
            DocMetadata newDoc = new DocMetadata();
            newDoc.setDocId(docId);
            newDoc.setName(name);
            newDoc.setOwnerId(ownerId);
            return newDoc;
        });

        // generate new codes (they change everytime session restarts)
        String newEditCode = generateReadableCode();
        String newViewCode = generateReadableCode();

        // update database with codes
        metadata.setEditCode(newEditCode);
        metadata.setViewCode(newViewCode);
        docRepository.save(metadata);

        // create sessioninfo object
        SessionInfo info = new SessionInfo();
        info.editCode = newEditCode;
        info.viewCode = newViewCode;

        // save the session into memory
        sessions.put(docId, info);

        return info;
    }

    // Endpoint to get codes for a document
    @GetMapping("docs/{documentId}/get-codes")
    public Map<String, String> getCodes(
            @PathVariable String documentId,
            @RequestParam String userId,
            @RequestParam(required = false) String name
    ) {
        String finalName = (name == null || name.isBlank()) ? "Untitled Document" : name;

        //finds existing doc or creates new one
        SessionInfo info = getOrCreateSession(documentId, finalName, userId);

        //return codes
        Map<String, String> codes = new HashMap<>();
        codes.put("editCode", info.editCode);
        codes.put("viewCode", info.viewCode);

        return codes;
    }

    @GetMapping("/join")
    public String join(@RequestParam String code, @RequestParam String userId) {
        for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
            String docId = entry.getKey();
            SessionInfo info = entry.getValue();
            DocMetadata doc = docRepository.findById(docId).orElse(null);
            String name = null;
            if (doc!=null) name = doc.getName();

            if (code.equals(info.editCode)) {
                if (!info.editors.contains(userId)) {
                    info.editors.add(userId);
                    System.out.println("new editor: " + userId);
                }
                // Format: role:docId:editCode:viewCode
                return "editor:" + docId + ":" + name + ":" + info.editCode + ":" + info.viewCode;
            } else if (code.equals(info.viewCode)) {
                if (!info.viewers.contains(userId)) {
                    info.viewers.add(userId);
                    System.out.println("new viewer: " + userId);
                }
                // For viewers, we still send the editCode so the UI can display it as "Hidden" or null
                return "viewer:" + docId + ":" + name + ":" + info.editCode + ":" + info.viewCode;
            }

            System.out.println(info.editors);
            System.out.println(info.viewers);

        }
        // if not found in current sessions search in db
        DocMetadata editDoc = docRepository.findByEditCode(code);
        if (editDoc != null) {
            SessionInfo info = new SessionInfo();
            info.editCode = editDoc.getEditCode();
            info.viewCode = editDoc.getViewCode();
            info.editors.add(userId);
            sessions.put(editDoc.getDocId(), info);
            System.out.println("new editor: " + userId);
            return "editor:" + editDoc.getDocId() + ":" + editDoc.getName() + ":"
                    + info.editCode + ":" + info.viewCode;
        }
        // maybe later allow user to view documents they were a viewer in
        DocMetadata viewDoc = docRepository.findByViewCode(code);
        if (viewDoc != null) {
            SessionInfo info = new SessionInfo();
            info.editCode = viewDoc.getEditCode();
            info.viewCode = viewDoc.getViewCode();
            info.viewers.add(userId);
            sessions.put(viewDoc.getDocId(), info);
            System.out.println("new viewer: " + userId);
            return "viewer:" + viewDoc.getDocId() + ":" + viewDoc.getName() + ":"
                    + info.editCode + ":" + info.viewCode;
        }
        // not found in sessions or database
        return "invalid";
    }

    @MessageMapping("/docs/{documentId}/send-data")
    public void sendUpdate(@DestinationVariable String documentId, Action update) {
        String userId = String.valueOf(update.getSiteID());
        SessionInfo info = sessions.get(documentId);
        boolean canEdit = info != null && info.editors.contains(userId);
        System.out.println("Recieved action: " + documentId + ", userId=" + userId + ", canEdit=" + canEdit);

        if (update.getActionType().equals("DISCONNECT")) {
            info.cursors.remove(userId);
            info.presences.remove(userId);
        }
        else if (update.getActionType().equals("CURSOR") && info != null && userId != null) {
            info.cursors.put(userId, update);
        }
        else if (update.getActionType().equals("PRESENCE"))
        {
            info.presences.put(userId, update);
        }
        else { //update last updated
            docRepository.updateLastModified(documentId,new Date());
        }
        if (canEdit && !update.getActionType().equals("CURSOR") &&
                !update.getActionType().equals("PRESENCE") &&
                !update.getActionType().equals("CURSOR_REMOVE") &&
                !update.getActionType().equals("DISCONNECT")) {
            actionRepository.save(update);
        }
        if (canEdit
                || update.getActionType().equals("CURSOR")
                || update.getActionType().equals("DISCONNECT")
                || update.getActionType().equals("CURSOR_REMOVE")
                || update.getActionType().equals("PRESENCE")) {
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
        if (info != null && !info.presences.isEmpty()) {
            history.addAll(info.presences.values());
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

    private void scheduleSessionCleanup(String docId) {
        scheduler.schedule(() -> {
            SessionInfo info = sessions.get(docId);
            // Only remove if the session is still empty after 5 minutes
            if (info != null && info.editors.isEmpty() && info.viewers.isEmpty()) {
                sessions.remove(docId);
                System.out.println("Session " + docId + " expired. Next join will generate new codes.");
            }
        }, 5, TimeUnit.MINUTES);
    }

    @PostMapping("/signup")
    public String signup(@RequestBody UserAccount user) {
        if (userRepository.existsByUsername(user.getUsername())) {
            return "username_not_unique";
        }
        userRepository.save(user);
        return user.getUserId();
    }

    @PostMapping("/login")
    public String login(@RequestBody UserAccount login) {
        Optional<UserAccount> found = userRepository.findByUsername(login.getUsername());
        if (found.isEmpty()) return "does_not_exist";
        if (!found.get().getPassword().equals(login.getPassword())) return "incorrect_password";
        return found.get().getUserId();
    }

    @GetMapping("/docs/{userId}")
    private List<DocMetadata> getDocs(@PathVariable String userId) {
        return docRepository.findByOwnerId(userId);
    }

    // temporary endpoint to view all users for testing only
    // instead of using mongodb ill copy paste https://apt-project-production-326d.up.railway.app/users in browser
    @GetMapping("/users")
    public List<UserAccount> getAllUsers() {
        return userRepository.findAll();
    }


    //version history endpoints

    @PostMapping("/docs/{documentId}/versions")
    public ResponseEntity<DocVersion> saveVersion(
            @PathVariable String documentId,
            @RequestBody VersionSaveRequest req) {

        List<Action> currentActions = actionRepository.findByDocumentId(documentId);

        String label = (req.getLabel() == null || req.getLabel().isBlank())
                ? "Version " + (docVersionRepository.findByDocIdOrderByCreatedAtDesc(documentId).size() + 1)
                : req.getLabel();

        String preview = req.getContent() == null ? "" : req.getContent();
        if (preview.length() > 500) preview = preview.substring(0, 500) + "…";

        String author = (req.getCreatedBy() == null || req.getCreatedBy().isBlank())
                ? "Anonymous" : req.getCreatedBy();

        DocVersion version = new DocVersion(documentId, label, currentActions, preview, author);
        return ResponseEntity.ok(docVersionRepository.save(version));
    }

    @GetMapping("/docs/{documentId}/versions")
    public ResponseEntity<List<VersionListItem>> listVersions(@PathVariable String documentId) {
        List<DocVersion> versions = docVersionRepository.findByDocIdOrderByCreatedAtDesc(documentId);
        List<VersionListItem> items = versions.stream()
                .map(v -> new VersionListItem(v.getId(), v.getLabel(),
                        v.getCreatedAt(), v.getCreatedBy(),
                        v.getContentPreview(),
                        v.getActions() == null ? 0 : v.getActions().size()))
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(items);
    }

    @PostMapping("/docs/{documentId}/versions/{versionId}/restore")
    public ResponseEntity<String> restoreVersion(
            @PathVariable String documentId,
            @PathVariable String versionId) {

        DocVersion version = docVersionRepository.findById(versionId).orElse(null);
        if (version == null || !version.getDocId().equals(documentId)) {
            return ResponseEntity.badRequest().body("Version not found");
        }


        actionRepository.deleteAll(actionRepository.findByDocumentId(documentId));

        if (version.getActions() != null) {
            actionRepository.saveAll(version.getActions());
        }

        docRepository.updateLastModified(documentId, new Date());

        Action restoreSignal = new Action();
        restoreSignal.setActionType("RESTORE");
        restoreSignal.setDocumentId(documentId);
        restoreSignal.setExtraData(versionId);
        messagingTemplate.convertAndSend("/topic/docs/" + documentId + "/updates", restoreSignal);

        return ResponseEntity.ok("restored");
    }

    // allow a user to delete a document
    @DeleteMapping("/docs/{docId}")
    public ResponseEntity<String> deleteDoc(@PathVariable String docId, @RequestParam String accountId) {
        DocMetadata doc = docRepository.findById(docId).orElse(null);
        if (doc == null) {
            return ResponseEntity.badRequest().body("not_found");
        }
        if (!doc.getOwnerId().equals(accountId)) {
            return ResponseEntity.status(403).body("not_owner");
        }
        docRepository.deleteById(docId);
        actionRepository.deleteByDocumentId(docId);
        docVersionRepository.deleteByDocId(docId); // also delete any versions
        sessions.remove(docId); // remove sessions if any
        return ResponseEntity.ok("deleted");
    }


    public static class VersionSaveRequest {
        private String label;
        private String createdBy;
        private String content;
        public String getLabel()     { return label; }
        public String getCreatedBy() { return createdBy; }
        public String getContent()   { return content; }
        public void setLabel(String l)     { this.label = l; }
        public void setCreatedBy(String u) { this.createdBy = u; }
        public void setContent(String c)   { this.content = c; }
    }

    public static class VersionListItem {
        private String id;
        private String label;
        private java.util.Date createdAt;
        private String createdBy;
        private String contentPreview;
        private int actionCount;

        public VersionListItem(String id, String label, java.util.Date createdAt,
                               String createdBy, String contentPreview, int actionCount) {
            this.id = id; this.label = label; this.createdAt = createdAt;
            this.createdBy = createdBy; this.contentPreview = contentPreview;
            this.actionCount = actionCount;
        }

        public String getId()             { return id; }
        public String getLabel()          { return label; }
        public java.util.Date getCreatedAt() { return createdAt; }
        public String getCreatedBy()      { return createdBy; }
        public String getContentPreview() { return contentPreview; }
        public int    getActionCount()    { return actionCount; }
    }
}
