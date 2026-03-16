package crdt.character;

import java.util.HashMap;

public class CharDLL implements ICRDT {
    private CharNode head; // sentinel
    private HashMap<String, CharNode> map;

    public CharDLL()
    {
        head = new CharNode(0, 0, '\0', "ROOT");
        head.setNext(null);
        map = new HashMap<>();
        map.put("ROOT", head);
    }

    @Override
    public void insert(CharNode c) throws RuntimeException {
        // Add to map
        map.put(c.getCharID(), c);

        // Find parent
        CharNode parent = map.get(c.getParentID());

        CharNode rightNeighbour = parent.getNext();
        CharNode leftNeighbour = parent;

        while (rightNeighbour != null)
        {
            // Edge case: Parent has no children
            if ((rightNeighbour == parent.getNext()) && !(sameParent(rightNeighbour, c))) break;

            // Normal case
            if (sameParent(rightNeighbour, c) && c.winsOver(rightNeighbour)) break;
            leftNeighbour = rightNeighbour;
            rightNeighbour = rightNeighbour.getNext();
        }
        c.setNext(rightNeighbour);
        c.setPrev(leftNeighbour);
        leftNeighbour.setNext(c);
        if (rightNeighbour != null)
            rightNeighbour.setPrev(c);
    }

    @Override
    public void delete(String id) throws RuntimeException {
        CharNode c = map.get(id);
        if (c == null) throw new RuntimeException("Node not found " + id);
        c.delete();
        // Will not remove from hashmap bec future inserts may still reference it as a parent
    }

    @Override
    public String collectText() {
        var text = new StringBuilder();
        CharNode vPtr = head.getNext();
        while(vPtr != null)
        {
            if (!vPtr.isDeleted()) text.append(vPtr.getContent());
            vPtr = vPtr.getNext();
        }
        return text.toString();
    }

    // Helper function for insert
    private boolean sameParent(CharNode c1, CharNode c2)
    {
        return c1.getParentID().equals(c2.getParentID());
    }
}
