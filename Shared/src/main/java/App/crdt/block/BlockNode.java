package App.crdt.block;

import App.crdt.character.CharDLL;

public class BlockNode {
    private final String blockID;
    private final long clock; // clock is a site-specific counter that's incremented with every op
    private final int siteID; // creator identifier
    private final long time;
    private final CharDLL content;

    // Mutable
    private int depth;
    private boolean isDeleted;
    private BlockNode next;
    private BlockNode prev;
    private String parentID; // null if parent is root

    public BlockNode(int siteID, long clock, long time, CharDLL content, String parentID)
    {
        this.clock = clock;
        this.siteID = siteID;
        this.blockID = siteID + "-" + clock;
        this.content = content;
        isDeleted = false;
        this.parentID = parentID;
        this.time = time;
    }
    // Sentinel constructor
    public BlockNode() {
        this.siteID = 0;
        this.clock = 0;
        this.time = 0;
        this.blockID = "ROOT";
        this.parentID = null;
        this.content = null;  // sentinel has no content
        this.isDeleted = false;
        this.depth = 0;
    }

    public boolean isDeleted() { return this.isDeleted; }
    public String getBlockID() { return blockID; }
    public CharDLL getContent() { return this.content; }
    public String getParentID() { return this.parentID; }
    public BlockNode getNext() { return this.next; }
    public int getDepth() { return this.depth; }
    public CharDLL copyContent(int SiteID,long clock,long time) { return this.content.copy(SiteID,clock,time); }
    public BlockNode getPrev() { return this.prev; }


    public void setNext(BlockNode n) { this.next = n; }
    public void setPrev(BlockNode p) { this.prev = p; }
    public void delete() { this.isDeleted = true; } // Mark as tombstone
    public void setDepth(int depth) { this.depth = depth; }
    public void setParentID(String parentID) { this.parentID = parentID; }

    public boolean winsOver(BlockNode other) throws RuntimeException {
        if (this.time != other.time)
            return this.time > other.time;
        return this.siteID < other.siteID;
    }
}
