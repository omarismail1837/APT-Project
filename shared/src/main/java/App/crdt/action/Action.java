package App.crdt.action;

import org.springframework.data.annotation.Id;import org.springframework.data.mongodb.core.index.Indexed;import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Objects;

@Document(collection = "actions")
public class Action {

    long clock; //used to identify unique actions along with siteID
    long time;
    int siteID;
    @Id
    String id;
    @Indexed
    String documentID;
    String actionType;
    String startCharID;
    String endCharID;
    String extraData;
    int colorIndex = -1;

    public Action(long clock, long time, int SiteID, String documentID, String actionType, String startCharID, String endCharID, String extraData) {
        this.clock = clock;
        this.siteID = SiteID;
        this.time = time;
        this.documentID = documentID;
        this.actionType = actionType;
        this.startCharID = startCharID;
        this.endCharID = endCharID;
        this.extraData = extraData; //holds JSON if pasting, character if inserting, true/false if italic/bold
        this.colorIndex = -1;
        this.id = getActionId();
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
    public int getColorIndex() {return colorIndex;}
    public String getId() {return id;}

    //setters
    public void setClock(long clock) {this.clock = clock;}
    public void setTime(long time) {this.time = time;}
    public void setSiteID(int siteID) {this.siteID = siteID;}
    public void setDocumentID(String documentID) {this.documentID = documentID;}
    public void setActionType(String actionType) {this.actionType = actionType;}
    public void setStartCharID(String startCharID) {this.startCharID = startCharID;}
    public void setEndCharID(String endCharID) {this.endCharID = endCharID;}
    public void setExtraData(String extraData) {this.extraData = extraData;}
    public void setColorIndex(int colorIndex) {this.colorIndex = colorIndex;}

    public String getActionId() {
        return documentID + ":" + siteID + ":" + clock;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Action other)) return false;
        return clock == other.clock
                && siteID == other.siteID
                && Objects.equals(documentID, other.documentID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clock, siteID, documentID);
    }

    @Override
    public String toString() {
        return "Action{" +
                "id='" + getActionId() + '\'' +
                ", type='" + actionType + '\'' +
                ", start='" + startCharID + '\'' +
                ", end='" + endCharID + '\'' +
                ", extra='" + extraData + '\'' +
                ", colorIndex='" + colorIndex + '\'' +
                '}';
    }


}
