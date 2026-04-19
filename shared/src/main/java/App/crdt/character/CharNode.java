package App.crdt.character;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CharNode {
    private final String charID;
    private final long clock; // clock is a site-specific counter that's incremented with every op
    private final int siteID; // creator identifier
    private final long time;
    private final char content;
    private String parentID;

    // Mutable
    private int depth;
    private boolean isDeleted;
    private boolean isBold;
    private boolean isItalic;
    @JsonIgnore //prevents infinite loop when sending data
    private CharNode next;
    @JsonIgnore //prevents infinite loop when sending data
    private CharNode prev;


    @JsonCreator
    public CharNode(
            @JsonProperty("siteID") int siteID,
            @JsonProperty("clock") long clock,
            @JsonProperty("time") long time,
            @JsonProperty("content") char content,
            @JsonProperty("parentID") String parentID,
            @JsonProperty("isBold") boolean isBold,
            @JsonProperty("isItalic") boolean isItalic,
            @JsonProperty("isDeleted") boolean isDeleted
    )    {
        this.clock = clock;
        this.siteID = siteID;
        this.charID = siteID + "-" + clock;
        this.content = content;
        this.parentID = parentID;
        this.time = time;
        this.isBold = isBold;
        this.isItalic = isItalic;
        this.isDeleted = isDeleted;
    }

    public CharNode(int siteID, long clock, long time, char content, String parentID, boolean isBold, boolean isItalic) {
        this.clock = clock;
        this.siteID = siteID;
        this.charID = siteID + "-" + clock;
        this.content = content;
        this.parentID = parentID;
        this.time = time;
        this.isBold = isBold;
        this.isItalic = isItalic;
        this.isDeleted = false;
    }

    // Constructor without bold & italic
    public CharNode(int siteID, long clock, long time, char content, String parentID)
    {
        this.clock = clock;
        this.siteID = siteID;
        this.charID = siteID + "-" + clock;
        this.content = content;
        isDeleted = false;
        this.parentID = parentID;
        this.time = time;
        this.isBold = false;
        this.isItalic = false;
    }

    public boolean getIsDeleted() { return this.isDeleted; }
    public String getCharID() { return charID; }
    public char getContent() { return this.content; }
    public String getParentID() { return this.parentID; }
    public CharNode getNext() { return this.next; }
    public int getDepth() { return this.depth; }
    public boolean getBold() {return this.isBold; }
    public boolean getItalic() {return this.isItalic; }
    public CharNode getPrev() { return this.prev; }


    public int getSiteID() { return this.siteID; }
    public long getClock() { return this.clock; }
    public long getTime() { return this.time; }

    public void setNext(CharNode n) { this.next = n; }
    public void setPrev(CharNode p) { this.prev = p; }
    public void delete() { this.isDeleted = true; } // Mark as tombstone
    public void setBold(boolean bold) { this.isBold = bold; }
    public void setItalic(boolean italic) { this.isItalic = italic; }
    public void setDepth(int depth) { this.depth = depth; }
    public void setParentID(String parentID) { this.parentID = parentID; }

    public boolean winsOver(CharNode other) throws RuntimeException {
        if (this.time != other.time) {
            return this.time > other.time;
        }
        if (this.clock != other.clock) {
            return this.clock > other.clock;
        }
        if (this.siteID != other.siteID) {
            return this.siteID < other.siteID;
        }
        return this.charID.compareTo(other.charID) < 0;
    }
}
