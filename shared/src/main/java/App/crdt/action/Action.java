package App.crdt.action;

public class Action {

    long clock; //used to identify unique actions along with siteID
    long time;
    int siteID;
    String documentID;
    String actionType;
    String startCharID;
    String endCharID;
    String extraData;

    public Action(long clock, long time, int SiteID, String documentID, String actionType, String startCharID, String endCharID, String extraData) {
        this.clock = clock;
        this.siteID = SiteID;
        this.time = time;
        this.documentID = documentID;
        this.actionType = actionType;
        this.startCharID = startCharID;
        this.endCharID = endCharID;
        this.extraData = extraData; //holds JSON if pasting, character if inserting, true/false if italic/bold
    }
    public Action() {} //required for JSON
    //getters
    public long getClock() {return clock;}
    public long getTime() {return time;}
    public int getSiteID() {return siteID;}
    public String getDocumentID() {return documentID;}
    public String getActionType() {return actionType;}
    public String getStartCharID() {return startCharID;}
    public String getEndCharID() {return endCharID;}
    public String getExtraData() {return extraData;}

    //setters
    public void setClock(long clock) {this.clock = clock;}
    public void setTime(long time) {this.time = time;}
    public void setSiteID(int siteID) {this.siteID = siteID;}
    public void setDocumentID(String documentID) {this.documentID = documentID;}
    public void setActionType(String actionType) {this.actionType = actionType;}
    public void setStartCharID(String startCharID) {this.startCharID = startCharID;}
    public void setEndCharID(String endCharID) {this.endCharID = endCharID;}
    public void setExtraData(String extraData) {this.extraData = extraData;}


}