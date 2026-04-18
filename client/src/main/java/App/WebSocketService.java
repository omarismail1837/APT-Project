package App;

import App.crdt.action.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Consumer;

public class WebSocketService {

    private StompSession session;
    private final Consumer<Action> onActionReceived;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSocketService(Consumer<Action> onActionReceived) {
        this.onActionReceived = onActionReceived;
    }

    public void connect(String url) {
        System.out.println("[WS] Connecting to: " + url);
        // Use SockJsClient to match the server's withSockJS() config
        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))
        );
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        stompClient.connectAsync(url, new StompSessionHandlerAdapter() {

            @Override
            public void afterConnected(StompSession s, StompHeaders headers) {
                session = s;
                System.out.println("[WS] Connected to server. sessionId=" + s.getSessionId());

                session.subscribe("/topic/updates", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return Action.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        System.out.println("[WS RECV topic] payloadType=" + payload.getClass().getName() + " payload=" + payload);
                        onActionReceived.accept((Action) payload);
                    }
                });
                System.out.println("[WS] Subscribed to /topic/updates");

                session.subscribe("/app/initial-state", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return List.class;
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public void handleFrame(StompHeaders headers, Object payload) {
                        List<?> rawList = (List<?>) payload;
                        System.out.println("[WS RECV init] items=" + rawList.size() + " payloadType=" + payload.getClass().getName());
                        for (Object item : rawList) {
                            if (item instanceof Action) {
                                System.out.println("[WS RECV init item] Action=" + item);
                                onActionReceived.accept((Action) item);
                            } else {
                                Action mapped = objectMapper.convertValue(item, Action.class);
                                System.out.println("[WS RECV init item] mapped Action=" + mapped);
                                onActionReceived.accept(mapped);
                            }
                        }
                    }
                });
                System.out.println("[WS] Subscribed to /app/initial-state");
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                System.err.println("Transport error: " + ex.getMessage());
            }
        });
    }

    public void sendAction(Action action) {
        if (session != null && session.isConnected()) {
            System.out.println("[WS SEND] /app/send-data action=" + action);
            session.send("/app/send-data", action);
        } else {
            System.err.println("[WS SEND FAIL] Not connected to server!");
        }
    }
}