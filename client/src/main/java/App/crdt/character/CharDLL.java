package App.crdt.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.util.*;

public class CharDLL implements ICRDT<CharNode> {
    private final CharNode head; // sentinel
    private final HashMap<String, CharNode> map;
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
        if (!c.getIsDeleted() && c.getContent() == '\n') lineCount--;
        //decrement line count if new line wasnt already deleted... important if 2 users delete line at same time
        c.delete();
        // Will not remove from hashmap bec future inserts may still reference it as a parent
    }

    public void undelete(String id) {
        CharNode c = map.get(id);
        if (c == null) return;
        if (c.getIsDeleted() && c.getContent() == '\n') lineCount++;
        c.undelete();
    }

    public void deleteRange(String startID, String endID) {
        CharNode ptr = map.get(startID);

        while (ptr != null) {
            delete(ptr.getCharID());
            if (endID != null && ptr.getCharID().equals(endID)) break;
            ptr = ptr.getNext();
        }

    }

    public void undeleteRange(String startID, String endID) {
        CharNode ptr = map.get(startID);

        while (ptr != null) {
            undelete(ptr.getCharID());
            if (endID != null && ptr.getCharID().equals(endID)) break;
            ptr = ptr.getNext();
        }

    }

    @Override
    public String collectText() {
        // Doesn't account for rich text yet
        var text = new StringBuilder();
        CharNode vPtr = head.getNext();
        while(vPtr != null)
        {
            if (!vPtr.getIsDeleted()) text.append(vPtr.getContent());
            vPtr = vPtr.getNext();
        }
        return text.toString();
    }


    // function needed in block operations to split a single block
    public CharDLL splitAt(int siteID, long clock, long time, String charID) {
        // Use a reserved sentinel site ID so split heads never collide with real character IDs.
        CharDLL newDLL = new CharDLL(-1*siteID, clock, time);
        CharNode ptr = map.get(charID);
        if (ptr == null) return newDLL;

        CharNode leftTail = ptr.getPrev();
        if (leftTail != null) {
            leftTail.setNext(null);
        }

        String prevID = newDLL.getHeadID();
        // In CharDLL.java - Modified splitAt snippet
        while (ptr != null) {
            CharNode next = ptr.getNext();

            // Instead of creating a 'movedNode' with a new ID:
            // 1. Remove from current map
            map.remove(ptr.getCharID());

            // 2. Clear pointers
            ptr.setPrev(null);
            ptr.setNext(null);

            // 3. Insert the EXACT same instance into the new DLL
            newDLL.insertExistingNode(ptr); // You'll need a method that doesn't generate new IDs

            ptr = next;
        }
        return newDLL;
    }

    private void insertExistingNode(CharNode node) {
        if (node == null) return;

        // 1. Ensure we aren't duplicating
        if (map.containsKey(node.getCharID())) return;

        // 2. Find the parent within THIS specific DLL
        CharNode parent = map.get(node.getParentID());
        if (parent == null) {
            // Fallback: If parent isn't found, attach to head to prevent data loss
            node.setParentID(head.getCharID());
            parent = head;
        }

        // 3. Register in map and update metadata
        map.put(node.getCharID(), node);
        if (node.getContent() == '\n') lineCount++;
        node.setDepth(parent.getDepth() + 1);

        // 4. Standard CRDT link logic (Handling potential concurrent inserts)
        CharNode rightNeighbour = parent.getNext();
        while (rightNeighbour != null && rightNeighbour.winsOver(node)) {
            parent = rightNeighbour;
            rightNeighbour = parent.getNext();
        }

        // 5. Stitch pointers
        node.setNext(rightNeighbour);
        node.setPrev(parent);
        parent.setNext(node);
        if (rightNeighbour != null) {
            rightNeighbour.setPrev(node);
        }
    }

    //function needed in block operations to merge two blocks
    public void mergeInto(CharDLL other) {
        if (other == null) return;

        CharNode tail = head;
        while (tail.getNext() != null) {
            tail = tail.getNext();
        }

        String prevID = tail.getCharID();
        CharNode otherPtr = other.head.getNext();
        while (otherPtr != null) {
            CharNode newNode = new CharNode(
                    otherPtr.getSiteID(),
                    otherPtr.getClock(),
                    otherPtr.getTime(),
                    otherPtr.getContent(),
                    prevID,
                    otherPtr.getBold(),
                    otherPtr.getItalic(),
                    otherPtr.getHighlighted()
            );

            if (!map.containsKey(newNode.getCharID())) {
                this.insert(newNode);
                if (otherPtr.getIsDeleted()) {
                    this.delete(newNode.getCharID());
                }
                prevID = newNode.getCharID();
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
            CharNode newNode = new CharNode(SiteID,++clock,time,temp.getContent(),parentID,temp.getBold(), temp.getItalic(), temp.getHighlighted());
            parentID = newNode.getCharID();
            clone.insert(newNode);
            temp = temp.getNext();
        }
        return clone;
    }
    public CharDLL copy(int siteID, long[] clockRef, long time, String startCharID, String endCharID) {
        CharDLL newDLL = new CharDLL(siteID,clockRef[0],time);
        CharNode ptr = startCharID == null ? head.getNext() : map.get(startCharID);
        if (ptr == null) return newDLL;
        String prevID = newDLL.head.getCharID();
        while (ptr != null) {
            if (!ptr.getIsDeleted()) {
                CharNode newNode = new CharNode(siteID, ++clockRef[0], ptr.getTime(), ptr.getContent(), prevID, ptr.getBold(), ptr.getItalic(), ptr.getHighlighted());
                newDLL.insert(newNode);
                prevID = siteID + "-" + clockRef[0];
            }
            if (ptr.getCharID().equals(endCharID)) break;
            ptr = ptr.getNext();
        }
        return newDLL;
    }
    public CharNode getHead() { return head; }
    public String getHeadID() { return head.getCharID(); }
    public String getCharIDAtLine(int lineNumber) {
        int count = 0;
        CharNode ptr = head.getNext();
        while (ptr != null) {
            if (!ptr.getIsDeleted() && ptr.getContent() == '\n') {
                count++;
                if (count == lineNumber && ptr.getNext() != null)
                    return ptr.getNext().getCharID();
            }
            ptr = ptr.getNext();
        }
        return null;
    }

    public void setIsItalic(String charID, boolean isItalic) {
        if (charID == null) return;
        CharNode character = map.get(charID);
        if (character == null) return;
        character.setItalic(isItalic);
    }

    public void setIsBold(String charID, boolean isBold) {
        if (charID == null) return;
        CharNode character = map.get(charID);
        if (character == null) return;
        character.setBold(isBold);
    }

    public String convertListToJson() {
        List<CharNode> nodes = new ArrayList<>();
        CharNode ptr = head.getNext();
        while (ptr != null) {nodes.add(ptr); ptr = ptr.getNext();}
        try {
            ObjectMapper mapper = new ObjectMapper();
            // takes list, respects @JsonIgnore, and creates a String
            return mapper.writeValueAsString(nodes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static CharDLL convertJSONToCharDLL(String json, int siteID, long clock, long time) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            // 1. Convert JSON string back into a temporary List of nodes
            List<CharNode> tempNodes = mapper.readValue(json, new TypeReference<List<CharNode>>(){});

            // 2. Create the wrapper object
            CharDLL newDLL = new CharDLL(siteID, clock, time);

            // 3. Stitch the pointers back together
            if (tempNodes != null && !tempNodes.isEmpty()) {
                tempNodes.get(0).setParentID(newDLL.getHeadID()); // change first node parent to root
                for (CharNode current : tempNodes) {
                    newDLL.insert(current);
                }
            }

            return newDLL;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void boldRange(String startchar, String endchar, Boolean isBold) {

        CharNode ptr = (startchar == null)? head.getNext() : map.get(startchar);

        while (ptr != null) {
            ptr.setBold(isBold);
            if (endchar != null && ptr.getCharID().equals(endchar)) break;
            ptr = ptr.getNext();
        }
    }

    public void italicRange(String startchar, String endchar, boolean isItalic) {

        CharNode ptr = (startchar == null)? head.getNext() : map.get(startchar);

        while (ptr != null) {
            ptr.setItalic(isItalic);
            if (endchar != null && ptr.getCharID().equals(endchar)) break;
            ptr = ptr.getNext();
        }

    }

    public String[] getAllCharIDs() {
        // 1. Copy the keys so we don't modify the actual map
        Set<String> allKeys = new HashSet<>(map.keySet());

        // 2. Remove the sentinel ID
        allKeys.remove(head.getCharID()); // or "ROOT"

        // 3. Return as array
        return allKeys.toArray(new String[0]);
    }

    public void collectFormattedText(XWPFParagraph text) {
        CharNode ptr = head.getNext();
        XWPFParagraph currentParagraph = text;

        while (ptr != null) {
            // advance over deleted characters but preserve structure
            if (!ptr.getIsDeleted()) {
                char c = ptr.getContent();

                // Newline: start a new paragraph
                if (c == '\n') {
                    // createParagraph is available on XWPFDocument via paragraph.getDocument()
                    try {
                        currentParagraph = currentParagraph.getDocument().createParagraph();
                    } catch (Exception e) {
                        // Fallback: if unable to create a new paragraph, append a newline char
                        XWPFRun run = currentParagraph.createRun();
                        run.setText("\n");
                    }
                } else {
                    XWPFRun run = currentParagraph.createRun();
                    run.setText(String.valueOf(c));
                    run.setBold(ptr.getBold());
                    run.setItalic(ptr.getItalic());
                }
            }
            ptr = ptr.getNext();
        }
    }
}
