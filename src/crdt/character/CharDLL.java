package crdt.character;

import java.util.HashMap;

public class CharDLL implements ICRDT {
    private CharNode head; // sentinel
    private HashMap<String, CharNode> map;

    public CharDLL()
    {
        head = new CharNode(0, 0, 0, '\0', "ROOT");
        head.setNext(null);
        map = new HashMap<>();
        map.put("ROOT", head);
    }

    @Override
    public void insert(CharNode c) {
        // Find parent
        CharNode parent = map.get(c.getParentID());

        if (parent == null) return;
        if (map.containsKey(c.getCharID())) return;

        map.put(c.getCharID(), c);
        int targetDepth = parent.getDepth() + 1;
        c.setDepth(targetDepth);

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
                if (c.winsOver(rightNeighbour)) break;
            }

            // Otherwise, currentDepth > targetDepth -> skip sibling's children
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
    public void delete(String id) {
        CharNode c = map.get(id);
        if (c == null) return;
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
}
