package crdt.character;

public class CharNode {
    private final String charID;
    private final long clock; // clock is a site-specific counter that's incremented with every op
    private final int siteID; // creator identifier
    private final long time;
    private final char content;
    private final String parentID; // null if parent is root

    // Mutable
    private int depth;
    private boolean isDeleted;
    private boolean isBold;
    private boolean isItalic;
    private CharNode next;
    private CharNode prev;


    public CharNode(int siteID, long clock, long time, char content, String parentID, boolean isBold, boolean isItalic)
    {
        this.clock = clock;
        this.siteID = siteID;
        this.charID = siteID + "-" + clock;
        this.content = content;
        isDeleted = false;
        this.parentID = parentID;
        this.time = time;
        this.isBold = isBold;
        this.isItalic = isItalic;
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

    public boolean isDeleted() { return this.isDeleted; }
    public String getCharID() { return charID; }
    public char getContent() { return this.content; }
    public String getParentID() { return this.parentID; }
    public CharNode getNext() { return this.next; }
    public int getDepth() { return this.depth; }

    public void setNext(CharNode n) { this.next = n; }
    public void setPrev(CharNode p) { this.prev = p; }
    public void delete() { this.isDeleted = true; } // Mark as tombstone
    public void setBold(boolean bold) { this.isBold = bold; }
    public void setItalic(boolean italic) { this.isItalic = italic; }
    public void setDepth(int depth) { this.depth = depth; }

    public boolean winsOver(CharNode other) throws RuntimeException {
        if (this.time != other.time)
            return this.time > other.time;
        return this.siteID < other.siteID;
    }
}
