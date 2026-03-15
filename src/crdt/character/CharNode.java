package crdt.character;

public class CharNode {
    private final String charID;
    private final long clock; // clock is a site-specific counter that's incremented with every op
    private final int siteID; // creator identifier
//    private final long time;
    private final char content;
    private final String parentID; // null if parent is root
    private CharNode next;
    private CharNode prev;

    // Mutable
    private boolean isDeleted;

    public CharNode(int siteID, long clock, char content, String parentID)
    {
        this.clock = clock;
        this.siteID = siteID;
        this.charID = siteID + "-" + clock;
        this.content = content;
        isDeleted = false;
        this.parentID = parentID;
//        time = System.currentTimeMillis();
    }

    public boolean isDeleted() { return this.isDeleted; }
    public String getCharID() { return charID; }
    public char getContent() { return this.content; }
    public String getParentID() { return this.parentID; }
    public CharNode getNext() { return this.next; }

    public void setNext(CharNode n) { this.next = n; }
    public void setPrev(CharNode p) { this.prev = p; }
    public void delete() { this.isDeleted = true; } // Mark as tombstone

    public boolean winsOver(CharNode other) throws RuntimeException {
//        if (this.time != other.time)
//            return this.time > other.time;
        if (this.clock != other.clock)
            return this.clock > other.clock;
        if (this.siteID != other.siteID)
            return this.siteID > other.siteID;
        throw new RuntimeException("Duplicate ID: " + this.charID);
    }
}
