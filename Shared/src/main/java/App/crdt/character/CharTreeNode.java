package App.crdt.character;

import java.util.ArrayList;

public class CharTreeNode {
    private final String charID;
    private final long clock;
    private final int siteID;
    private final long time;
    private final char content;
    private String parentID;

    // Mutable
    private boolean isDeleted;
    private boolean isBold;
    private boolean isItalic;

    // Tree field
    private final ArrayList<CharTreeNode> children;

    public CharTreeNode(int siteID, long clock, long time, char content, String parentID, boolean isBold, boolean isItalic)
    {
        this.clock = clock;
        this.siteID = siteID;
        this.charID = siteID + "-" + clock;
        this.content = content;
        this.isDeleted = false;
        this.parentID = parentID;
        this.time = time;
        this.isBold = isBold;
        this.isItalic = isItalic;
        this.children = new ArrayList<>();
    }

    // Constructor without bold & italic
    public CharTreeNode(int siteID, long clock, long time, char content, String parentID)
    {
        this(siteID, clock, time, content, parentID, false, false);
    }

    // Insert child in sorted position
    public void addChild(CharTreeNode child)
    {
        int i = 0;
        while (i < children.size() && children.get(i).winsOver(child))
            i++;
        children.add(i, child);
    }

    public ArrayList<CharTreeNode> getChildren() { return children; }

    public boolean isDeleted()    { return this.isDeleted; }
    public String getCharID()     { return charID; }
    public char getContent()      { return this.content; }
    public String getParentID()   { return this.parentID; }
    public boolean getBold()      { return this.isBold; }
    public boolean getItalic()    { return this.isItalic; }

    public void delete()                 { this.isDeleted = true; }
    public void setBold(boolean bold)    { this.isBold = bold; }
    public void setItalic(boolean italic){ this.isItalic = italic; }

    public boolean winsOver(CharTreeNode other)
    {
        if (this.time != other.time)
            return this.time > other.time;
        return this.siteID < other.siteID;
    }
}