package App;

import App.crdt.action.Action;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    @MessageMapping("/send-data")
    @SendTo("/topic/updates") // broadcasts return value to everyone
    public Action sendUpdate(Action update) {
        System.out.println("Processing: " + update.getActionType() + " from " + update.getStartCharID() + " to " + update.getEndCharID() + " with extra data: " + update.getExtraData());

        // 2. Apply it to Server-Side CRDT structure
        // myCrdtEngine.apply(update);

        // 3. Return the update so Spring sends it to all other users
        return update;
    }

}