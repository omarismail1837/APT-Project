package App;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Destination for messages going FROM server TO client
        config.enableSimpleBroker("/topic");
        // Destination for messages coming FROM client TO server
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The URL clients use to connect (ws://localhost:8080/ws-connect)
        registry.addEndpoint("/ws-connect").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Increase limits to handle large initial state batches
        registration.setMessageSizeLimit(2 * 1024 * 1024); // 2MB max per message
        registration.setSendBufferSizeLimit(4 * 1024 * 1024); // 4MB total buffer
        registration.setSendTimeLimit(20000); // Allow 20s for slow clients
    }
}