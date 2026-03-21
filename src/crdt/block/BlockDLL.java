package crdt.block;

import crdt.character.CharDLL;
import crdt.character.CharNode;
import crdt.character.ICRDT;
import java.util.HashMap;

public class BlockDLL implements ICRDT<BlockNode> {
    private final BlockNode head; // sentinel
    private final HashMap<String, BlockNode> map;
    private final HashMap<String, String> charBlockMap;

    public BlockDLL() {
        head = new BlockNode();
        head.setNext(null);
        map = new HashMap<>();
        charBlockMap = new HashMap<>();
        map.put("ROOT", head);
    }
    public String getBlockIDByCharID(String charID) {
        return charBlockMap.get(charID);
    }
    @Override
    public void insert(BlockNode b) {
        BlockNode parent = map.get(b.getParentID());

        if (parent == null) return;
        if (map.containsKey(b.getBlockID())) return;

        map.put(b.getBlockID(), b);
        CharNode ptr = b.getContent().getHead().getNext();
        while (ptr != null) {
            if (!ptr.isDeleted()) charBlockMap.put(ptr.getCharID(), b.getBlockID());
            ptr = ptr.getNext();
        }
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

    public BlockNode splitBlock(int siteID, long clock, long time, String blockID, String charID) {
        BlockNode original = map.get(blockID);
        if (original == null || original.isDeleted()) return null;

        // Split the CharDLL at charID
        crdt.character.CharDLL newContent = original.getContent().splitAt(siteID,clock,time,charID);
        BlockNode newBlock = new BlockNode(
                siteID,
                clock,
                System.currentTimeMillis(),
                newContent,
                blockID
        );
        CharNode ptr = newBlock.getContent().getHead().getNext();
        while (ptr != null) {
            if (!ptr.isDeleted()) charBlockMap.put(ptr.getCharID(), newBlock.getBlockID());
            ptr = ptr.getNext();
        }
        insert(newBlock);
        return newBlock;
    }

    public void mergeBlocks(String firstBlockID, String secondBlockID) {
        BlockNode first = map.get(firstBlockID);
        BlockNode second = map.get(secondBlockID);

        if (first == null || second == null) return;
        if (first.isDeleted() || second.isDeleted()) return;
        CharNode ptr = second.getContent().getHead().getNext();
        while (ptr != null) {
            charBlockMap.put(ptr.getCharID(), firstBlockID);
            ptr = ptr.getNext();
        }
        first.getContent().mergeInto(second.getContent());
        second.delete();
    }

    public void moveBlock(String BlockID, String TargetBlockID) {
        //TargetBlockID is the block before where it should be moved to
        BlockNode moving = map.get(BlockID);
        BlockNode target = map.get(TargetBlockID);

        if (moving == null || target == null) return;

        BlockNode targetNext = target.getNext();


        BlockNode leftNeighbour = moving.getPrev();
        BlockNode rightNeighbour = moving.getNext();

        leftNeighbour.setNext(moving.getNext());
        if (rightNeighbour != null) rightNeighbour.setPrev(leftNeighbour);

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

        moving.setNext(targetNext);
        moving.setPrev(target);
        target.setNext(moving);
        if (targetNext != null) targetNext.setPrev(moving);
        moving.setDepth(target.getDepth()+1); //it is a child of target to be directly after
        moving.setParentID(target.getBlockID());

    }

    public CharDLL copyBlock(String BlockID, int SiteID, long clock,long time) {
        BlockNode original = map.get(BlockID);
        return original.copyContent(SiteID,clock,time);
    }

    public void pasteBlock(CharDLL pasted, String blockID, int SiteID, long clock, long time) {
        if (pasted == null) return;
        BlockNode target = map.get(blockID);
        if (target == null || target.isDeleted()) return;
        CharDLL copied = pasted.copy(SiteID, clock + 1, time);
        if (copied == null) return;
        BlockNode newBlock = new BlockNode(SiteID, clock, time, copied, target.getBlockID());
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
    public void autosplit(int siteID, long clock, long time, String blockID) {
        //split lines after 10th and merge to the one after
        //if it cant fit in block after then split block after and merge....
        BlockNode updatedBlock = map.get(blockID);

        if (updatedBlock == null) return;

        if (updatedBlock.getContent().getLineCount() <= 10) return;
        String CharID = updatedBlock.getContent().getCharIDAtLine(10);
        if (CharID == null) return;
        BlockNode newBlock = splitBlock(siteID, clock, time, blockID, CharID);
        if (newBlock == null) return;

        BlockNode nextBlock = newBlock.getNext();

        while (nextBlock != null && nextBlock.isDeleted())
            nextBlock = nextBlock.getNext();
        if (nextBlock == null) return;

        mergeBlocks(newBlock.getBlockID(), nextBlock.getBlockID());
        autosplit(siteID, clock + 1, time, newBlock.getBlockID());

    }

    //if line count < 2 then merge block with block before or after - should be checked when deleting
    public void automerge(String blockID, int siteID, long clock, long time) {
        BlockNode updatedBlock = map.get(blockID);
        if (updatedBlock == null) return;
        int currentLineCount = updatedBlock.getContent().getLineCount();
        if (currentLineCount >= 2) return;

        //get previous not deleted block
        BlockNode previousBlock = updatedBlock.getPrev();
        while (previousBlock != null && previousBlock.isDeleted()) {previousBlock = previousBlock.getPrev();}
        if (previousBlock != null && previousBlock.getContent() != null && previousBlock.getContent().getLineCount() <= (10 - currentLineCount)) {
            mergeBlocks(previousBlock.getBlockID(), blockID);
            return;
        }

        //get next not deleted block
        BlockNode nextBlock = updatedBlock.getNext();
        while (nextBlock != null && nextBlock.isDeleted()) {nextBlock = nextBlock.getNext();}
        if (nextBlock != null && nextBlock.getContent() != null && nextBlock.getContent().getLineCount() <= (10 - currentLineCount)) {
            mergeBlocks(blockID, nextBlock.getBlockID());
            return;
        }
        mergeBlocks(blockID, nextBlock.getBlockID());
        autosplit(siteID, clock, time, blockID);
        //if it cant be merged to block before or block after
    }
    public void insertChar(String parentCharID, CharNode newChar) {
        String blockID = charBlockMap.get(parentCharID);
        if (blockID == null) return;

        BlockNode block = map.get(blockID);
        if (block == null || block.isDeleted()) return;

        block.getContent().insert(newChar);
        charBlockMap.put(newChar.getCharID(), blockID);
        autosplit(newChar.getSiteID(), newChar.getClock() + 1, newChar.getTime(), blockID);
    }
    public void deleteChar(String charID, int siteID, long clock, long time) {
        String blockID = charBlockMap.get(charID);
        if (blockID == null) return;

        BlockNode block = map.get(blockID);
        if (block == null) return;
        block.getContent().delete(charID);

        automerge(charID, siteID, time, clock );
    }
    public void replaceChar(String oldCharID, CharNode newChar,int siteID, long clock, long time) {
        String blockID = charBlockMap.get(oldCharID);
        deleteChar(oldCharID, siteID, clock, time);
        if (blockID == null) return;
        BlockNode block = map.get(blockID);
        if (block == null || block.isDeleted()) return;
        block.getContent().insert(newChar);
        charBlockMap.put(newChar.getCharID(), blockID);
        autosplit(newChar.getSiteID(), newChar.getClock() + 1, newChar.getTime(), blockID);
    }

    public CharDLL copyBlockContent(String blockID, int siteID, long clock, long time, String startCharID) {
        BlockNode block = map.get(blockID);
        if (block == null || block.isDeleted()) return null;
        return block.getContent().copy(siteID, clock, time, startCharID);
    }
    public void pasteBlockContent(int siteID, long clock, long time, String targetBlockID, String charID, CharDLL copied) {

        BlockNode secondHalf = splitBlock(siteID, clock, time, targetBlockID, charID);
        BlockNode first = map.get(targetBlockID);
        first.getContent().mergeInto(copied);
        if (secondHalf != null) {
            first.getContent().mergeInto(secondHalf.getContent());
            secondHalf.delete();
        }
        autosplit(siteID, clock + 1, time, targetBlockID);
    }

}