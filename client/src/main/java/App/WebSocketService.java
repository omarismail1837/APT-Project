package App;

//import App.crdt.action.Action;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
package App.crdt.action;

public class Action {

    long clock; //used to identify unique actions along with siteID
    int siteID;
    String documentID;
    String actionType;
    String startCharID;
    String endCharID;
    String extraData;

    public Action(long clock, int SiteID, String documentID, String actionType, String startCharID, String endCharID, String extraData) {
        this.clock = clock;
        this.siteID = SiteID;
        this.documentID = documentID;
        this.actionType = actionType;
        this.startCharID = startCharID;
        this.endCharID = endCharID;
        this.extraData = extraData;
    }
    public Action() {} //required for JSON
    //getters
    public long getClock() {return clock;}
    public int getSiteID() {return siteID;}
    public String getDocumentID() {return documentID;}
    public String getActionType() {return actionType;}
    public String getStartCharID() {return startCharID;}
    public String getEndCharID() {return endCharID;}
    public String getExtraData() {return extraData;}

    //setters
    public void setClock(long clock) {this.clock = clock;}
    public void setSiteID(int siteID) {this.siteID = siteID;}
    public void setDocumentID(String documentID) {this.documentID = documentID;}
    public void setActionType(String actionType) {this.actionType = actionType;}
    public void setStartCharID(String startCharID) {this.startCharID = startCharID;}
    public void setEndCharID(String endCharID) {this.endCharID = endCharID;}
    public void setExtraData(String extraData) {this.extraData = extraData;}


}
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
                        return Action.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        onActionReceived.accept((Action) payload);
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