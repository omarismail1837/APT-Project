package crdt.character;

public class CharNode {
    // Set once @construction
    private final String charID;
    private final int siteID; // creator identifier
    private final int counter; // Sequential counter rel to parent
    private final char content;
    private final String parentID; // null if parent is root

    // Mutable
    private boolean isDeleted;

    public CharNode(int siteID, long clock, int counter, char content, String parentID)
    {
        // clock is a site-specific counter that's incremented with every op
        this.siteID = siteID;
        this.charID = siteID + "-" + clock;
        this.counter = counter;
        this.content = content;
        isDeleted = false;
        this.parentID = parentID;
    }

    public boolean isDeleted() { return this.isDeleted; } // Mark as tombstone
    public String getCharID() { return charID; }
    public char getContent() { return this.content; }
    public String getParentID() { return this.parentID; }
    public int getCounter() { return this.counter; }
    public int getSiteID() { return this.siteID; }

    public void delete() { this.isDeleted = true; }
}
