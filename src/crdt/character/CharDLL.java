package crdt.character;

import java.util.HashMap;

public class CharDLL implements ICRDT<CharNode> {
    private final CharNode head; // sentinel
    private HashMap<String, CharNode> map;
    private int lineCount;

    public CharDLL(int siteID, long clock, long time)
    {
        head = new CharNode(siteID, clock, time, '\0', "ROOT");
        head.setNext(null);
        map = new HashMap<>();
        map.put(head.getCharID(), head);
        lineCount = 0;
    }

    public int getLineCount() { return lineCount; }

    @Override
    public void insert(CharNode node) {
        // Find parent
        CharNode parent = map.get(node.getParentID());

        if (parent == null) return;
        if (map.containsKey(node.getCharID())) return;

        map.put(node.getCharID(), node);
        if (node.getContent() == '\n') lineCount++;
        int targetDepth = parent.getDepth() + 1;
        node.setDepth(targetDepth);

        CharNode rightNeighbour = parent.getNext();
        CharNode leftNeighbour = parent;

        while (rightNeighbour != null)
        {
            int currentDepth = rightNeighbour.getDepth();

            // Too shallow -> either parent has no children yet or node lost to all children -> insert here
            if (currentDepth < targetDepth) break;

            // Sibling -> check winsOver
            if (currentDepth == targetDepth)
            {
                // Same depth implies sibling; cousins are unreachable because
                // their parent (depth targetDepth-1) would trigger the shallow break first
                if (node.winsOver(rightNeighbour)) break;
            }

            // Otherwise, currentDepth > targetDepth -> skip sibling's children
            leftNeighbour = rightNeighbour;
            rightNeighbour = rightNeighbour.getNext();
        }

        node.setNext(rightNeighbour);
        node.setPrev(leftNeighbour);
        leftNeighbour.setNext(node);
        if (rightNeighbour != null)
            rightNeighbour.setPrev(node);
    }

    @Override
    public void delete(String id) {
        CharNode c = map.get(id);
        if (c == null) return;
        if (!c.isDeleted() && c.getContent() == '\n') lineCount--;
        //decrement line count if new line wasnt already deleted... important if 2 users delete line at same time
        c.delete();
        // Will not remove from hashmap bec future inserts may still reference it as a parent
    }

    @Override
    public String collectText() {
        // Doesn't account for rich text yet
        var text = new StringBuilder();
        CharNode vPtr = head.getNext();
        while(vPtr != null)
        {
            if (!vPtr.isDeleted()) text.append(vPtr.getContent());
            vPtr = vPtr.getNext();
        }
        return text.toString();
    }


    //function needed in block operations to split a single block
    public CharDLL splitAt(int siteID, long clock, long time, String charID) {
        CharDLL newDLL = new CharDLL(siteID, clock, time);
        CharNode ptr = map.get(charID);
        if (ptr == null) return newDLL;
        String prevID = newDLL.head.getCharID();
        while (ptr != null) {
            if (!ptr.isDeleted()) {
                CharNode newNode = new CharNode(
                        ptr.getSiteID(),
                        ptr.getClock(),
                        ptr.getTime(),
                        ptr.getContent(),
                        prevID,
                        ptr.getBold(),
                        ptr.getItalic()
                );
                newDLL.insert(newNode);
                prevID = ptr.getSiteID() + "-" + ptr.getClock();
            }
            this.delete(ptr.getCharID());
            ptr = ptr.getNext();
        }
        return newDLL;
    }

   //function needed in block operations to merge two blocks
    public void mergeInto(CharDLL other) {
        //get last node from current chardll
        CharNode lastNode = head.getNext();

        while (lastNode != null) {
            if (lastNode.getNext() == null) break;
            lastNode = lastNode.getNext();
        }

        String prevID = (lastNode == null? head.getCharID(): lastNode.getCharID());
        CharNode otherPtr = other.head.getNext();
        while (otherPtr != null) {
            if (!otherPtr.isDeleted()) {
                CharNode newNode = new CharNode(
                        otherPtr.getSiteID(),
                        otherPtr.getClock(),
                        otherPtr.getTime(),
                        otherPtr.getContent(),
                        prevID,
                        otherPtr.getBold(),
                        otherPtr.getItalic()
                );
                this.insert(newNode);
                prevID = otherPtr.getSiteID() + "-" + otherPtr.getClock();
            }
            otherPtr = otherPtr.getNext();
        }
    }

    //function needed in block operations to copy
    public CharDLL copy(int SiteID,long clock,long time) {
        CharDLL clone = new CharDLL(SiteID, clock, time);

        CharNode temp = head.getNext(); //start from the node after head (the first node after root)
        String parentID = clone.head.getCharID(); //all heads (roots) have same ID
        while (temp != null) {
            //make a copy of temp charnode and insert into clone
            CharNode newNode = new CharNode(SiteID,clock++,time,temp.getContent(),parentID,temp.getBold(), temp.getItalic());
            parentID = newNode.getCharID();
            clone.insert(newNode);
            temp = temp.getNext();
        }
        return clone;
    }
    public CharDLL copy(int siteID, long clock, long time, String startCharID) {
        CharDLL newDLL = new CharDLL(siteID,clock,time);
        CharNode ptr = startCharID == null ? head.getNext() : map.get(startCharID);
        if (ptr == null) return newDLL;
        String prevID = newDLL.head.getCharID();
        while (ptr != null) {
            if (!ptr.isDeleted()) {
                CharNode newNode = new CharNode(siteID, clock, ptr.getTime(), ptr.getContent(), prevID, ptr.getBold(), ptr.getItalic());
                newDLL.insert(newNode);
                prevID = siteID + "-" + clock;
                clock++;
            }
            ptr = ptr.getNext();
        }
        return newDLL;
    }
    public CharNode getHead() { return head; }
    public String getCharIDAtLine(int lineNumber) {
        int count = 0;
        CharNode ptr = head.getNext();
        while (ptr != null) {
            if (!ptr.isDeleted() && ptr.getContent() == '\n') {
                count++;
                if (count == lineNumber && ptr.getNext() != null)
                    return ptr.getNext().getCharID();
            }
            ptr = ptr.getNext();
        }
        return null;
    }
}
