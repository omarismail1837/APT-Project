package crdt.block;

import crdt.character.ICRDT;
import java.util.HashMap;

public class BlockDLL implements ICRDT<BlockNode> {
    private BlockNode head; // sentinel
    private HashMap<String, BlockNode> map;

    public BlockDLL() {
        head = new BlockNode();
        head.setNext(null);
        map = new HashMap<>();
        map.put("ROOT", head);
    }

    @Override
    public void insert(BlockNode b) {
        BlockNode parent = map.get(b.getParentID());

        if (parent == null) return;
        if (map.containsKey(b.getBlockID())) return;

        map.put(b.getBlockID(), b);
        int targetDepth = parent.getDepth() + 1;
        b.setDepth(targetDepth);

        BlockNode rightNeighbour = parent.getNext();
        BlockNode leftNeighbour = parent;

        while (rightNeighbour != null) {
            int currentDepth = rightNeighbour.getDepth();

            if (currentDepth < targetDepth) break;

            if (currentDepth == targetDepth) {
                if (b.winsOver(rightNeighbour)) break;
            }

            leftNeighbour = rightNeighbour;
            rightNeighbour = rightNeighbour.getNext();
        }

        b.setNext(rightNeighbour);
        b.setPrev(leftNeighbour);
        leftNeighbour.setNext(b);
        if (rightNeighbour != null)
            rightNeighbour.setPrev(b);
    }

    @Override
    public void delete(String blockID) {
        BlockNode b = map.get(blockID);
        if (b == null) return;
        b.delete();
    }

    public BlockNode splitBlock(int siteID, long clock, String blockID, String charID) {
        BlockNode original = map.get(blockID);
        if (original == null || original.isDeleted()) return null;

        // Split the CharDLL at charID, returns a new CharDLL with everything from charID onwards
        crdt.character.CharDLL newContent = original.getContent().splitAt(charID);

        // New block is a child of the original block in the tree
        BlockNode newBlock = new BlockNode(
                siteID,
                clock,
                System.currentTimeMillis(),
                newContent,
                blockID
        );

        insert(newBlock);
        return newBlock;
    }

    public void mergeBlocks(String firstBlockID, String secondBlockID) {
        BlockNode first = map.get(firstBlockID);
        BlockNode second = map.get(secondBlockID);

        if (first == null || second == null) return;
        if (first.isDeleted() || second.isDeleted()) return;

        first.getContent().mergeInto(second.getContent());
        second.delete();
    }

    @Override
    public String collectText() {
        StringBuilder text = new StringBuilder();
        BlockNode ptr = head.getNext();
        while (ptr != null) {
            if (!ptr.isDeleted())
                text.append(ptr.getContent().collectText()).append("\n");
            ptr = ptr.getNext();
        }
        return text.toString();
    }

    public BlockNode getBlock(String blockID) {
        return map.get(blockID);
    }
}