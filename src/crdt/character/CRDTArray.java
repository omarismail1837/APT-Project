package crdt.character;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class CRDTArray implements ICRDT {
    private ArrayList<CharNode> nodes;
    private HashMap<String, CharNode> map; // O(1) lookup

    public CRDTArray() {
        nodes = new ArrayList<>();
        map = new HashMap<>();
        map.put(null, null);
    }

    @Override
    public void delete(CharNode charNode)
    {
        charNode.delete();
        // Tombstones are not deleted from the map so that future inserts can still find their parent
    }

    @Override
    public String collectText() {
        StringBuilder sb = new StringBuilder();
        for (CharNode c : nodes) {
            if (!c.isDeleted()) sb.append(c.getContent());
        }
        return sb.toString();
    }

    @Override
    // Used by doc layer
    public CharNode getNode(String id)
    {
        return map.get(id);
    }

    @Override
    public void insert(CharNode c)
    {
        map.put(c.getCharID(), c);
        int insertIdx = nodes.indexOf(map.get(c.getParentID())) + 1;
        int counter = c.getCounter();
        int siteID = c.getSiteID();

        HashSet<String> visited = new HashSet<>();
        visited.add(c.getParentID());

        while (insertIdx < nodes.size())
        {
            CharNode currentNode = nodes.get(insertIdx);

            if (!visited.contains(currentNode.getParentID())) break;

            int currentCounter = currentNode.getCounter();

            if ((SameParent(c, currentNode)) && (currentCounter < counter))
            {
                break;
            }

            if ((SameParent(c, currentNode)) && (currentCounter == counter))
            {
                if (siteID < currentNode.getSiteID())
                {
                    break;
                }
            }
            visited.add(currentNode.getCharID()); // Mark currentNode as visited so that its children get skipped
            insertIdx++;
        }
        nodes.add(insertIdx, c);
    }

    private boolean SameParent(CharNode c1, CharNode c2)
    {
        // Check for nulls first to avoid NullPointerException
        if (c1.getParentID() == null && c2.getParentID() == null) return true;
        if (c1.getParentID() == null || c2.getParentID() == null) return false;
        return c1.getParentID().equals(c2.getParentID());
    }

    public void printTree() {
        System.out.println("ROOT");
        printChildren(null, nodes, 1);
    }

    private void printChildren(String parentID, List<CharNode> nodes, int depth) {
        String indent = "  ".repeat(depth);
        for (CharNode node : nodes) {
            boolean isChild = (parentID == null && node.getParentID() == null)
                    || (parentID != null && parentID.equals(node.getParentID()));
            if (isChild) {
                String deleted = node.isDeleted() ? "DELETED" : "";
                System.out.println(indent + "'" + node.getContent() + "'" + deleted + " [id=" + node.getCharID() + ", counter=" + node.getCounter() + "]");
                printChildren(node.getCharID(), nodes, depth + 1);
            }
        }
    }
}
