package crdt.character;

import java.util.HashMap;

public class CharTree implements ICRDT<CharTreeNode> {
    private final CharTreeNode root; // sentinel
    private final HashMap<String, CharTreeNode> map;
    private int lineCount;

    public CharTree(int siteID, long clock, long time) {
        root = new CharTreeNode(siteID, clock, time, '\0', "ROOT");
        map = new HashMap<>();
        map.put(root.getCharID(), root);
        lineCount = 0;
    }

    public int getLineCount() {
        return lineCount;
    }

    public CharTreeNode getRoot() {
        return root;
    }

    public String getRootID() {
        return root.getCharID();
    }

    @Override
    public void insert(CharTreeNode node) {
        CharTreeNode parent = map.get(node.getParentID());
        if (parent == null) return;
        if (map.containsKey(node.getCharID())) return;

        map.put(node.getCharID(), node);
        parent.addChild(node);
        if (node.getContent() == '\n') lineCount++;
    }

    @Override
    public void delete(String id) {
        CharTreeNode c = map.get(id);
        if (c == null) return;
        if (!c.isDeleted() && c.getContent() == '\n') lineCount--;
        c.delete();
    }

    @Override
    public String collectText() {
        StringBuilder sb = new StringBuilder();
        dfs(root, sb);
        return sb.toString();
    }

    // DFS with stack
    private void dfs(CharTreeNode node, StringBuilder sb) {
        // push children in reverse so the highest-priority child is processed first
        java.util.ArrayDeque<CharTreeNode> stack = new java.util.ArrayDeque<>();

        java.util.ArrayList<CharTreeNode> rootChildren = node.getChildren();
        for (int i = rootChildren.size() - 1; i >= 0; i--)
            stack.push(rootChildren.get(i));

        while (!stack.isEmpty()) {
            CharTreeNode current = stack.pop();
            if (!current.isDeleted()) sb.append(current.getContent());

            java.util.ArrayList<CharTreeNode> children = current.getChildren();
            for (int i = children.size() - 1; i >= 0; i--)
                stack.push(children.get(i));
        }
    }

    public void setIsBold(String charID, boolean isBold) {
        if (charID == null) return;
        CharTreeNode c = map.get(charID);
        if (c == null) return;
        c.setBold(isBold);
    }

    public void setIsItalic(String charID, boolean isItalic) {
        if (charID == null) return;
        CharTreeNode c = map.get(charID);
        if (c == null) return;
        c.setItalic(isItalic);
    }

    public String getCharIDAtLine(int lineNumber) {
        java.util.ArrayDeque<CharTreeNode> stack = new java.util.ArrayDeque<>();
        java.util.ArrayList<CharTreeNode> rootChildren = root.getChildren();
        for (int i = rootChildren.size() - 1; i >= 0; i--)
            stack.push(rootChildren.get(i));

        int count = 0;
        boolean foundNewline = false;

        while (!stack.isEmpty()) {
            CharTreeNode current = stack.pop();

            if (foundNewline && !current.isDeleted())
                return current.getCharID();

            if (!current.isDeleted() && current.getContent() == '\n') {
                count++;
                if (count == lineNumber) foundNewline = true;
            }

            java.util.ArrayList<CharTreeNode> children = current.getChildren();
            for (int i = children.size() - 1; i >= 0; i--)
                stack.push(children.get(i));
        }
        // ok to return null because autosplit guards against it
        return null;
    }

    // Block ops
    public CharTree splitAt(int siteID, long clock, long time, String charID)
    {
        CharTree newTree = new CharTree(siteID, clock, time);
        CharTreeNode splitPoint = map.get(charID);
        if (splitPoint == null) return newTree;

        java.util.ArrayDeque<CharTreeNode> stack = new java.util.ArrayDeque<>();
        stack.push(splitPoint);

        // Local map to splitAt
        java.util.HashMap<String, CharTreeNode> newParentMap = new java.util.HashMap<>();
        newParentMap.put(splitPoint.getParentID(), newTree.root);

        while (!stack.isEmpty())
        {
            CharTreeNode current = stack.pop();

            // Remove node from old map and from old children list
            CharTreeNode oldParent = map.get(current.getParentID());
            if (oldParent != null) oldParent.getChildren().remove(current);
            map.remove(current.getCharID());
            // Dec old tree's count
            if (!current.isDeleted() && current.getContent() == '\n') lineCount--;

            // If parent is not in the new hashmap, use newTree.root
            CharTreeNode newParent = newParentMap.getOrDefault(current.getParentID(), newTree.root);
            newParent.addChild(current);
            newTree.map.put(current.getCharID(), current);

            // Inc new tree's count
            if (!current.isDeleted() && current.getContent() == '\n') newTree.lineCount++;
            newParentMap.put(current.getCharID(), current);

            for (int i = current.getChildren().size() - 1; i >= 0; i--)
                stack.push(current.getChildren().get(i));
        }
        return newTree;
    }

    public void mergeInto(CharTree other) {
        CharTreeNode last = getLastNode();
        CharTreeNode anchor = last == null ? root : last;

        for (CharTreeNode child : other.root.getChildren()) {
            map.put(child.getCharID(), child);
            anchor.addChild(child);
            // re-register all descendants to map
            registerDescendants(child);
        }
        lineCount += other.lineCount;
    }

    private void registerDescendants(CharTreeNode node)
    {
        java.util.ArrayDeque<CharTreeNode> stack = new java.util.ArrayDeque<>();
        for (CharTreeNode child : node.getChildren())
            stack.push(child);

        while (!stack.isEmpty())
        {
            CharTreeNode current = stack.pop();
            map.put(current.getCharID(), current);
            for (CharTreeNode child : current.getChildren())
                stack.push(child);
        }
    }

    private CharTreeNode getLastNode() {
        CharTreeNode current = root;
        while (!current.getChildren().isEmpty())
            current = current.getChildren().get(current.getChildren().size() - 1);
        return current == root ? null : current;
    }

    // TODO: Add copy
}

