package crdt.character;

import java.util.HashMap;

public class CharDLL implements ICRDT<CharNode> {
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
    public void insert(CharNode node) {
        // Find parent
        CharNode parent = map.get(node.getParentID());

        if (parent == null) return;
        if (map.containsKey(node.getCharID())) return;

        map.put(node.getCharID(), node);
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
    public CharDLL splitAt(String charID) {
        CharDLL newDLL = new CharDLL();
        CharNode ptr = map.get(charID);
        if (ptr == null) return newDLL;
        String prevID = "ROOT";
        while (ptr != null) {
            if (!ptr.isDeleted()) {
                CharNode newNode = new CharNode(
                        ptr.getSiteID(),
                        ptr.getClock(),
                        ptr.getTime(),
                        ptr.getContent(),
                        prevID
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
        CharNode ptr = head.getNext();
        CharNode lastNode = null;
        while (ptr != null) {
            if (!ptr.isDeleted()) lastNode = ptr;
            ptr = ptr.getNext();
        }

        String prevID = lastNode == null ? "ROOT" : lastNode.getCharID();
        CharNode otherPtr = other.head.getNext();
        while (otherPtr != null) {
            if (!otherPtr.isDeleted()) {
                CharNode newNode = new CharNode(
                        otherPtr.getSiteID(),
                        otherPtr.getClock(),
                        otherPtr.getTime(),
                        otherPtr.getContent(),
                        prevID
                );
                this.insert(newNode);
                prevID = otherPtr.getSiteID() + "-" + otherPtr.getClock();
            }
            otherPtr = otherPtr.getNext();
        }
    }
}
