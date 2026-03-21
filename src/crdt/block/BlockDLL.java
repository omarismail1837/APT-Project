package crdt.block;

import crdt.character.CharDLL;
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

    public void moveBlock(String BlockID, String TargetBlockID) {
        //TargetBlockID is the block before where it should be moved to
        BlockNode moving = map.get(BlockID);
        BlockNode target = map.get(TargetBlockID);

        if (moving == null || target == null) return;

        BlockNode leftNeighbour = moving.getPrev();

        leftNeighbour.setNext(moving.getNext());
        moving.setNext((target.getNext()));

        //leftneighbour should inherit children of moving
        BlockNode temp = leftNeighbour.getNext();
        int movingDepth = moving.getDepth();
        String movingID = moving.getBlockID();
        while (temp != null && temp.getDepth() > movingDepth) { //while temp is a child of moving
            if (temp.getParentID().equals(movingID))
               temp.setParentID(leftNeighbour.getBlockID());

            temp.setDepth(temp.getDepth()-1);
            temp = temp.getNext();
        }

        target.setNext(moving);
        moving.setDepth(target.getDepth()+1); //it is a child of target to be directly after
        moving.setParentID(target.getBlockID());

    }

    public CharDLL copyBlock(String BlockID, int SiteID, long clock,long time) {
        BlockNode original = map.get(BlockID);
        return original.copyContent(SiteID,clock,time);
    }

    public void pasteBlock(CharDLL pasted, BlockNode target, int SiteID, long clock, long time) {
        if (!map.containsValue(target)) return;
        BlockNode newBlock = new BlockNode(SiteID, clock, time, pasted.copy(SiteID, clock+1, time), target.getBlockID());
        insert(newBlock);
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

    //auto merge and split
    //if line count > 10 then split block - should be checked when inserting and pasting
    public void autosplit(String blockID) {
        BlockNode updatedBlock = map.get(blockID);
        if (updatedBlock == null) return;

        if (updatedBlock.getContent().getLineCount() <= 10) return;

        //split lines after 10th and merge to the one after
        //if it cant fit in block after then split block after and merge....

    }

    //if line count < 2 then merge block with block before or after - should be checked when deleting
    public void automerge(String blockID) {
        BlockNode updatedBlock = map.get(blockID);
        if (updatedBlock == null) return;
        int currentLineCount = updatedBlock.getContent().getLineCount();
        if (currentLineCount >= 2) return;

        //get previous not deleted block
        BlockNode previousBlock = updatedBlock.getPrev();
        while (previousBlock.isDeleted()) {previousBlock = previousBlock.getPrev();}
        if (previousBlock.getContent().getLineCount() <= (10 - currentLineCount)) {
            //merge
        }

        //get next not deleted block
        BlockNode nextBlock = updatedBlock.getNext();
        while (nextBlock.isDeleted()) {nextBlock = nextBlock.getNext();}
        if (nextBlock.getContent().getLineCount() <= (10 - currentLineCount)) {
            //merge
        }

        //if it cant be merged to block before or block after
    }

}