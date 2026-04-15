package App;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    // Clients send data to /app/send-data
    @MessageMapping("/send-data")
    // Everyone subscribed to /topic/updates receives the result
    @SendTo("/topic/updates")
    public String handleUpdate(String message) {
        System.out.println("Received: " + message);
        return "Server says: " + message;
    }
}