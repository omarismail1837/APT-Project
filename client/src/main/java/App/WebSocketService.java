package App;

import App.crdt.action.Action;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
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

    public WebSocketService(Consumer<Action> onActionReceived) {
        this.onActionReceived = onActionReceived;
    }

    public void connect(String url) {
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
                System.out.println("Connected to server!");

                session.subscribe("/topic/updates", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return Action.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        onActionReceived.accept((Action) payload);
                    }
                });

                session.subscribe("/app/initial-state", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return List.class;
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public void handleFrame(StompHeaders headers, Object payload) {
                        List<?> rawList = (List<?>) payload;
                        for (Object item : rawList) {
                            if (item instanceof Action) {
                                onActionReceived.accept((Action) item);
                            }
                        }
                    }
                });
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                System.err.println("Transport error: " + ex.getMessage());
            }
        });
    }

    public void sendAction(Action action) {
        if (session != null && session.isConnected()) {
            session.send("/app/send-data", action);
        } else {
            System.err.println("Not connected to server!");
        }
    }
}