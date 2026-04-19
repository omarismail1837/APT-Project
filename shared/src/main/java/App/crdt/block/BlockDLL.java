package App.crdt.block;

import App.crdt.action.Action;
import App.crdt.character.CharDLL;
import App.crdt.character.CharNode;
import App.crdt.character.ICRDT;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@Service
public class BlockDLL implements ICRDT<BlockNode> {
    private final BlockNode head; // sentinel
    private final HashMap<String, BlockNode> map;
    private final HashMap<String, String> charBlockMap;
    private List<Action> allActions;
    private Set<String> appliedActionIds;

    public BlockDLL() {
        head = new BlockNode();
        head.setNext(null);
        map = new HashMap<>();
        charBlockMap = new HashMap<>();
        map.put("ROOT", head);
        allActions = new ArrayList<>();
        appliedActionIds = new HashSet<>();

        // Shared deterministic bootstrap so all replicas have the same initial parent IDs.
        ensureSeedHeadID();
    }
    public String getBlockIDByCharID(String charID) {
        return charBlockMap.get(charID);
    }
    @Override
    public void insert(BlockNode b) {
        if (b == null || b.getContent() == null) return;
        BlockNode parent = map.get(b.getParentID());

        if (parent == null) return;
        if (map.containsKey(b.getBlockID())) return;

        map.put(b.getBlockID(), b);
        // Map CharDLL sentinel head as a valid insertion anchor for this block.
        charBlockMap.put(b.getContent().getHeadID(), b.getBlockID());
        CharNode ptr = b.getContent().getHead().getNext();
        while (ptr != null) {
            charBlockMap.put(ptr.getCharID(), b.getBlockID());
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

    public BlockNode splitBlock(int siteID, long clock, long time, String charID) {
        String blockID = charBlockMap.get(charID);
        BlockNode original = map.get(blockID);
        if (original == null || original.isDeleted()) return null;

        // Split the CharDLL at charID
        CharDLL newContent = original.getContent().splitAt(siteID,clock,time,charID);
        BlockNode newBlock = new BlockNode(
                siteID,
                clock,
                System.currentTimeMillis(),
                newContent,
                blockID
        );
        CharNode ptr = newBlock.getContent().getHead().getNext();
        while (ptr != null) {
            charBlockMap.put(ptr.getCharID(), newBlock.getBlockID());
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
        CharNode ptr = second.getContent().getHead();
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

    //can be deleted
//    public CharDLL copyBlock(String BlockID, int SiteID, long clock,long time) {
//        BlockNode original = map.get(BlockID);
//        if (original == null || original.isDeleted()) return null;
//        return original.copyContent(SiteID,clock,time);
//    }
//
//    public void pasteBlock(CharDLL pasted, String blockID, int SiteID, long clock, long time) {
//        if (pasted == null) return;
//        BlockNode target = map.get(blockID);
//        if (target == null || target.isDeleted()) return;
//        CharDLL copied = pasted.copy(SiteID, clock + 1, time);
//        if (copied == null) return;
//        BlockNode newBlock = new BlockNode(SiteID, clock, time, copied, target.getBlockID());
//        insert(newBlock);
//    }

    @Override
    public String collectText() {
        StringBuilder text = new StringBuilder();
        BlockNode ptr = head.getNext();
        while (ptr != null) {
            if (!ptr.isDeleted()) {
                text.append(ptr.getContent().collectText());
            }
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
        BlockNode oldBlock = map.get(blockID);
        if (oldBlock == null || oldBlock.getContent() == null) return;

        // 1. Find the 11th line start
        String splitCharID = findSplitPoint(oldBlock.getContent(), 10);
        if (splitCharID == null) return;

        // 2. Perform the split
        // We use a high offset for the split clock or pass the current controller clock
        // to ensure the new block ID is unique and doesn't "clash" with typed chars.
        CharDLL movedContent = oldBlock.getContent().splitAt(siteID, ++clock, time, splitCharID);

        if (movedContent != null) {
            // 3. Create the new block
            BlockNode newBlock = new BlockNode(siteID, ++clock, time, movedContent, oldBlock.getBlockID());

            // 4. Update the character-to-block mapping BEFORE inserting
            // This is the most critical step for the cursor stability
            for (String cid : movedContent.getAllCharIDs()) {
                charBlockMap.put(cid, newBlock.getBlockID());
            }

            // 5. Insert the new block into the Block CRDT
            this.insert(newBlock);
        }
    }

    private String findSplitPoint(CharDLL content, int lineThreshold) {
        // Start after the ROOT sentinel node
        CharNode ptr = content.getHead().getNext();
        int newlineCount = 0;

        while (ptr != null) {
            if (ptr.getContent() == '\n') {
                newlineCount++;
            }
            // If we hit the 10th newline, the next node is the split point
            if (newlineCount == lineThreshold) {
                CharNode nextNode = ptr.getNext();
                return (nextNode != null) ? nextNode.getCharID() : null;
            }
            ptr = ptr.getNext();
        }
        return null;
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
        if (nextBlock == null) return;

        while (nextBlock != null && nextBlock.isDeleted()) {nextBlock = nextBlock.getNext();}
        if (nextBlock != null && nextBlock.getContent() != null && nextBlock.getContent().getLineCount() <= (10 - currentLineCount)) {
            mergeBlocks(blockID, nextBlock.getBlockID());
            return;
        }
        if(nextBlock != null ){
        mergeBlocks(blockID, nextBlock.getBlockID());
        autosplit(siteID, clock, time, blockID);
        }
        else if (previousBlock != head){
            mergeBlocks( previousBlock.getBlockID(),blockID);
            autosplit(siteID, clock, time,  previousBlock.getBlockID());}
    }
    public void insertChar(String parentCharID, CharNode newChar) {
        System.out.println("[BlockDLL.insertChar] parent=" + parentCharID + " newChar=" + (newChar == null ? "null" : newChar.getCharID()));
        String blockID = charBlockMap.get(parentCharID);
        if (blockID == null) {
            System.out.println("[BlockDLL.insertChar] SKIP parent not in charBlockMap");
            return;
        }

        BlockNode block = map.get(blockID);
        if (block == null) {
            System.out.println("[BlockDLL.insertChar] SKIP block not in map blockID=" + blockID);
            return;
        }
        if (block.isDeleted()) {
            System.out.println("[BlockDLL.insertChar] SKIP block deleted blockID=" + blockID);
            return;
        }

        block.getContent().insert(newChar);
        charBlockMap.put(newChar.getCharID(), blockID);
        System.out.println("[BlockDLL.insertChar] OK blockID=" + blockID);
        autosplit(newChar.getSiteID(), newChar.getClock() + 1, newChar.getTime(), blockID);
    }

    //can be deleted?
    public void deleteChar(String charID, int siteID, long clock, long time) {
        System.out.println("[BlockDLL.deleteChar] charID=" + charID);
        String blockID = charBlockMap.get(charID);
        if (blockID == null) {
            System.out.println("[BlockDLL.deleteChar] SKIP char not in charBlockMap");
            return;
        }

        BlockNode block = map.get(blockID);
        if (block == null) {
            System.out.println("[BlockDLL.deleteChar] SKIP block not in map blockID=" + blockID);
            return;
        }
        block.getContent().delete(charID);
        System.out.println("[BlockDLL.deleteChar] OK blockID=" + blockID);
        automerge(blockID, siteID, clock, time);
    }

    public void deleteChars(String startCharID, String endCharID, int siteID, long clock, long time) {
        String startBlockID = charBlockMap.get(startCharID);
        String endBlockID = charBlockMap.get(endCharID);
        if (startBlockID == null || endBlockID == null) return;

        BlockNode startBlockNode = map.get(startBlockID);
        BlockNode endBlockNode = map.get(endBlockID);
        if (startBlockNode == null || endBlockNode == null) return;

        BlockNode currentBlock = startBlockNode;
        String currentCharID = startCharID;

        while (currentBlock != null) {
            String startchar = (currentBlock == startBlockNode) ? startCharID : null;
            String endchar = (currentBlock == endBlockNode) ? endCharID : null;
            currentBlock.getContent().deleteRange(startchar, endchar);
            if (currentBlock == endBlockNode) break;
            currentBlock = currentBlock.getNext();
        }

        currentBlock = startBlockNode;
        while (currentBlock != null) {
            automerge(currentBlock.getBlockID(), siteID, clock, time);
            if (currentBlock.getBlockID() == endBlockID) break;
            currentBlock = currentBlock.getNext();
        }

    }

   //can be deleted
//    public void replaceChar(String oldCharID, CharNode newChar,int siteID, long clock, long time) {
//        String blockID = charBlockMap.get(oldCharID);
//        deleteChar(oldCharID, siteID, clock, time);
//        if (blockID == null) return;
//        BlockNode block = map.get(blockID);
//        if (block == null || block.isDeleted()) return;
//        block.getContent().insert(newChar);
//        charBlockMap.put(newChar.getCharID(), blockID);
//        autosplit(newChar.getSiteID(), newChar.getClock() + 1, newChar.getTime(), blockID);
//    }

  //can be deleted
//    public void setIsBold(String charID, boolean isBold) {
//        if (charID == null) return;
//        String blockID = charBlockMap.get(charID);
//        if (blockID == null) return;
//        BlockNode block = map.get(blockID);
//        if (block == null || block.isDeleted()) return;
//        block.getContent().setIsBold(charID, isBold);
//    }
//    public void setIsItalic(String charID, boolean isItalic) {
//        if (charID == null) return;
//        String blockID = charBlockMap.get(charID);
//        if (blockID == null) return;
//        BlockNode block = map.get(blockID);
//        if (block == null || block.isDeleted()) return;
//        block.getContent().setIsItalic(charID, isItalic);
//    }

    public String copyBlockContent(int siteID, long clock, long time, String startCharID, String endCharID) {
        String startBlockID = charBlockMap.get(startCharID);
        if (startBlockID == null) return null;
        String endBlockID = endCharID == null ? null : charBlockMap.get(endCharID);
        if (endCharID != null && endBlockID == null) return null;
        CharDLL result = new CharDLL(siteID, clock, time);
        BlockNode currentBlock = map.get(startBlockID);
        long[] clockRef = { clock };
        while (currentBlock != null) {
            if (!currentBlock.isDeleted()) {
                String start = currentBlock.getBlockID().equals(startBlockID) ? startCharID : null;
                String end = currentBlock.getBlockID().equals(endBlockID) ? endCharID : null;
                clockRef[0]++;
                CharDLL blockCopy = currentBlock.getContent().copy(siteID, clockRef, time, start, end);
                result.mergeInto(blockCopy);
            }
            if (currentBlock.getBlockID().equals(endBlockID)) break;
            currentBlock = currentBlock.getNext();
        }
        return result.convertListToJson();
    }
    public void pasteBlockContent(int siteID, long clock, long time, String charID, String copiedJSON) {

        CharDLL copied = CharDLL.convertJSONToCharDLL(copiedJSON, siteID, clock, time);
        String targetBlockID = charBlockMap.get(charID);
        if (targetBlockID == null) return;
        CharDLL temp = copied.copy(siteID, clock, time);
        BlockNode secondHalf = splitBlock(siteID, clock, time, charID);
        BlockNode first = map.get(targetBlockID);
        first.getContent().mergeInto(temp);
        if (secondHalf != null) {
            first.getContent().mergeInto(secondHalf.getContent());
            secondHalf.delete();
        }
        autosplit(siteID, clock + 1, time, targetBlockID);
    }

    //Action Functions

    private void ensureActionsListInitialized() {
        if (allActions == null) {
            allActions = new ArrayList<>();
        }
        if (appliedActionIds == null) {
            appliedActionIds = new HashSet<>();
            for (Action action : allActions) {
                if (action != null) {
                    appliedActionIds.add(buildActionId(action));
                }
            }
        }
    }

    public synchronized String ensureSeedHeadID() {
        BlockNode root = map.get("ROOT");
        if (root == null) {
            return null;
        }

        BlockNode first = root.getNext();
        if (first != null && first.getContent() != null) {
            String headID = first.getContent().getHeadID();
            charBlockMap.put(headID, first.getBlockID());
            return headID;
        }

        CharDLL seedContent = new CharDLL(0, 1, 0L);
        BlockNode seedBlock = new BlockNode(0, 2, 0L, seedContent, "ROOT");
        insert(seedBlock);

        BlockNode created = root.getNext();
        if (created == null || created.getContent() == null) {
            return null;
        }
        String headID = created.getContent().getHeadID();
        charBlockMap.put(headID, created.getBlockID());
        return headID;
    }

    public synchronized void applyAction(Action update) {
        if (update == null) return;
        ensureActionsListInitialized();
        String actionId = buildActionId(update);
        if (appliedActionIds.contains(actionId)) {
            return;
        }
        appliedActionIds.add(actionId);
        allActions.add(update);

        String startCharID = update.getStartCharID();
        String endCharID = update.getEndCharID();
        String extraData = update.getExtraData();
        long clock = update.getClock();
        int siteID = update.getSiteID();
        long time = update.getTime();

        //apply action
        switch(update.getActionType()) {
            case "DELETE":
                if (endCharID == null || endCharID.isBlank()) {
                    deleteChar(startCharID, siteID, clock, time);
                } else {
                    deleteChars(startCharID, endCharID, siteID, clock, time);
                }
                break;

            case "INSERT":
                if (extraData != null && !extraData.isEmpty()) {
                    char inserted = extraData.charAt(0);
                    if (inserted == '\r') {
                        inserted = '\n';
                    }
                    insertChar(startCharID, new CharNode(siteID, clock, time, inserted, startCharID));
                }
                break;

            case "PASTE":
                pasteBlockContent(siteID, clock, time, startCharID, extraData);
                break;

            case "BOLD":
                setIsBoldRange(startCharID, endCharID, Boolean.parseBoolean(extraData));
                break;

            case "ITALIC":
                setIsItalicRange(startCharID, endCharID, Boolean.parseBoolean(extraData));
                break;

            default:
                break;

        }

    }

    private void setIsItalicRange(String startCharID, String endCharID, boolean b) {

        String startBlockID = charBlockMap.get(startCharID);
        String endBlockID = charBlockMap.get(endCharID);
        if (startBlockID == null || endBlockID == null) return;

        BlockNode startBlockNode = map.get(startBlockID);
        BlockNode endBlockNode = map.get(endBlockID);
        if (startBlockNode == null || endBlockNode == null) return;

        BlockNode currentBlock = startBlockNode;
        String currentCharID = startCharID;

        while (currentBlock != null) {
            String startchar = (currentBlock == startBlockNode) ? startCharID : null;
            String endchar = (currentBlock == endBlockNode) ? endCharID : null;
            currentBlock.getContent().italicRange(startchar, endchar, b);
            if (currentBlock == endBlockNode) break;
            currentBlock = currentBlock.getNext();
        }

    }

    private void setIsBoldRange(String startCharID, String endCharID, boolean b) {

        String startBlockID = charBlockMap.get(startCharID);
        String endBlockID = charBlockMap.get(endCharID);
        if (startBlockID == null || endBlockID == null) return;

        BlockNode startBlockNode = map.get(startBlockID);
        BlockNode endBlockNode = map.get(endBlockID);
        if (startBlockNode == null || endBlockNode == null) return;

        BlockNode currentBlock = startBlockNode;
        String currentCharID = startCharID;

        while (currentBlock != null) {
            String startchar = (currentBlock == startBlockNode) ? startCharID : null;
            String endchar = (currentBlock == endBlockNode) ? endCharID : null;
            currentBlock.getContent().boldRange(startchar, endchar, b);
            if (currentBlock == endBlockNode) break;
            currentBlock = currentBlock.getNext();
        }

    }

    public List<Action> getAllActions() {
        ensureActionsListInitialized();
        return new ArrayList<>(allActions);
    }

    private String buildActionId(Action action) {
        if (action == null) {
            return "null";
        }
        return action.getDocumentID() + ":" + action.getSiteID() + ":" + action.getClock();
    }

    public BlockNode getHead() {
        return head;
    }
}