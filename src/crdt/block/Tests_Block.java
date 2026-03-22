package crdt.block;

import crdt.character.CharDLL;
import crdt.character.CharNode;

public class Tests_Block {
    private static int passes = 0;
    private static int failures = 0;

    public static void main() {
        testBasicInsertAndCollect();
        testDeleteBlock();
        testSplitBlock();
        testMergeBlocks();
        testMoveBlock();
        testCopyAndPasteBlock();
        testInsertChar();
        testDeleteChar();
        testReplaceChar();
        testAutosplit();
        testAutomerge();
        testCopyBlockContent();
        testPasteBlockContent();
        testOrdering();
        testEdgeCases();
        System.out.println(passes + (passes == 1 ? " Pass & " : " Passes & ") + failures + (failures == 1 ? " Failure" : " Failures"));
    }

    private static void check(String testName, boolean cond) {
        if (cond) {
            System.out.println("PASS: " + testName);
            passes++;
        } else {
            System.out.println("FAIL: " + testName);
            failures++;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Global clock shared by all helper-created CharNodes.
     * Always incrementing guarantees every CharNode gets a unique charID
     * ("8-N") even when two helpers are called in the same test, which
     * prevents the duplicate-insert guard from silently dropping nodes during
     * merge / copy / paste operations.
     */
    private static long globalClock = 1;

    /** Build a CharDLL whose visible text equals {@code text}. */
    private static CharDLL makeCharDLL(String text) {
        // The sentinel of CharDLL(0, globalClock, 0) gets charID "0-" + globalClock.
        // We capture that ID before incrementing so we know the parent for the first node.
        long headClock = globalClock++;
        CharDLL dll = new CharDLL(0, headClock, 0);
        String prevID = "0-" + headClock;
        for (int i = 0; i < text.length(); i++) {
            long c = globalClock++;
            CharNode cn = new CharNode(8, c, 1, text.charAt(i), prevID);
            dll.insert(cn);
            prevID = "8-" + c;
        }
        return dll;
    }

    /** Build a CharDLL that contains exactly one newline character (1 line). */
    private static CharDLL makeNewlineDLL() {
        long headClock = globalClock++;
        CharDLL dll = new CharDLL(0, headClock, 0);
        long c = globalClock++;
        dll.insert(new CharNode(8, c, 1, '\n', "0-" + headClock));
        return dll;
    }

    /** Build a CharDLL with {@code lines} newline characters. */
    private static CharDLL makeMultilineDLL(int lines) {
        long headClock = globalClock++;
        CharDLL dll = new CharDLL(0, headClock, 0);
        String prev = "0-" + headClock;
        for (int i = 0; i < lines; i++) {
            long c = globalClock++;
            dll.insert(new CharNode(8, c, 1, '\n', prev));
            prev = "8-" + c;
        }
        return dll;
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    private static void testBasicInsertAndCollect() {
        // Empty BlockDLL
        BlockDLL bdll = new BlockDLL();
        check("Empty BlockDLL produces empty text", bdll.collectText().isEmpty());

        // Single block inserted as child of ROOT
        BlockDLL bdll2 = new BlockDLL();
        CharDLL content = makeCharDLL("hello");
        BlockNode b = new BlockNode(1, 0, 1, content, "ROOT");
        bdll2.insert(b);
        check("Single block inserted produces its text", bdll2.collectText().equals("hello\n"));

        // Two blocks in sequence
        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("first"), "ROOT"));
        bdll3.insert(new BlockNode(1, 1, 2, makeCharDLL("second"), "1-0"));
        String text3 = bdll3.collectText();
        check("Two sequential blocks both appear in output", text3.contains("first") && text3.contains("second"));

        // Duplicate insert is ignored
        BlockDLL bdll4 = new BlockDLL();
        BlockNode dup = new BlockNode(1, 0, 1, makeCharDLL("dup"), "ROOT");
        bdll4.insert(dup);
        bdll4.insert(dup);
        check("Duplicate block insert is silently ignored", bdll4.collectText().equals("dup\n"));

        // Insert with unknown parent is ignored
        BlockDLL bdll5 = new BlockDLL();
        bdll5.insert(new BlockNode(1, 0, 1, makeCharDLL("orphan"), "nonexistent-parent"));
        check("Insert with unknown parent is silently ignored", bdll5.collectText().isEmpty());

        // getBlock returns correct node
        BlockDLL bdll6 = new BlockDLL();
        BlockNode target = new BlockNode(2, 5, 1, makeCharDLL("find me"), "ROOT");
        bdll6.insert(target);
        check("getBlock returns the correct block by ID", bdll6.getBlock("2-5") == target);

        // getBlock returns null for missing ID
        check("getBlock returns null for unknown ID", bdll6.getBlock("99-99") == null);

        // getBlockIDByCharID returns correct mapping after insert
        BlockDLL bdll7 = new BlockDLL();
        CharDLL cdll7 = new CharDLL(0, 0, 0);
        cdll7.insert(new CharNode(3, 0, 1, 'x', "0-0"));
        bdll7.insert(new BlockNode(1, 0, 1, cdll7, "ROOT"));
        check("getBlockIDByCharID returns block ID for a char inside it",
                "1-0".equals(bdll7.getBlockIDByCharID("3-0")));
    }

    private static void testDeleteBlock() {
        // Basic delete hides block from collectText
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("visible"), "ROOT"));
        bdll.delete("1-0");
        check("Deleted block is excluded from collected text", bdll.collectText().isEmpty());

        // Double delete is idempotent
        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(1, 0, 1, makeCharDLL("hi"), "ROOT"));
        bdll2.delete("1-0");
        try {
            bdll2.delete("1-0");
            check("Double delete is silently ignored", true);
        } catch (Exception e) {
            check("Double delete is silently ignored", false);
        }

        // Delete of non-existent ID is silently ignored
        BlockDLL bdll3 = new BlockDLL();
        try {
            bdll3.delete("fake-99");
            check("Delete of non-existent block ID is silently ignored", true);
        } catch (Exception e) {
            check("Delete of non-existent block ID is silently ignored", false);
        }

        // Deleted block is marked via isDeleted()
        BlockDLL bdll4 = new BlockDLL();
        BlockNode bn = new BlockNode(1, 0, 1, makeCharDLL("mark"), "ROOT");
        bdll4.insert(bn);
        bdll4.delete("1-0");
        check("Deleted block has isDeleted() == true", bn.isDeleted());
    }

    private static void testSplitBlock() {
        // Split produces a new block with the tail characters
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        // Insert chars: A B \n C D \n
        cdll.insert(new CharNode(1, 0, 1, 'A', "0-0"));
        cdll.insert(new CharNode(1, 1, 2, 'B', "1-0"));
        cdll.insert(new CharNode(1, 2, 3, '\n', "1-1"));
        cdll.insert(new CharNode(1, 3, 4, 'C', "1-2"));
        cdll.insert(new CharNode(1, 4, 5, 'D', "1-3"));
        cdll.insert(new CharNode(1, 5, 6, '\n', "1-4"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        BlockNode newBlock = bdll.splitBlock(2, 0, 2, "1-0", "1-3");
        check("splitBlock returns a non-null new block", newBlock != null);
        check("splitBlock: new block contains tail characters",
                newBlock.getContent().collectText().contains("D"));

        // splitBlock on a non-existent block returns null
        BlockDLL bdll2 = new BlockDLL();
        BlockNode result = bdll2.splitBlock(1, 0, 1, "nonexistent", "somechar");
        check("splitBlock on non-existent block returns null", result == null);

        // splitBlock on a deleted block returns null
        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("abc"), "ROOT"));
        bdll3.delete("1-0");
        BlockNode result3 = bdll3.splitBlock(1, 1, 1, "1-0", "9-0");
        check("splitBlock on deleted block returns null", result3 == null);

        // charBlockMap is updated for characters in the new block
        BlockDLL bdll4 = new BlockDLL();
        CharDLL cdll4 = new CharDLL(0, 0, 0);
        cdll4.insert(new CharNode(1, 0, 1, 'X', "0-0"));
        cdll4.insert(new CharNode(1, 1, 2, 'Y', "1-0"));
        cdll4.insert(new CharNode(1, 2, 3, '\n', "1-1"));
        cdll4.insert(new CharNode(1, 3, 4, 'Z', "1-2"));
        cdll4.insert(new CharNode(1, 4, 5, '\n', "1-3"));
        bdll4.insert(new BlockNode(1, 0, 1, cdll4, "ROOT"));
        BlockNode splitResult = bdll4.splitBlock(2, 0, 2, "1-0", "1-3");
        check("splitBlock updates charBlockMap for chars moved to new block",
                splitResult != null && splitResult.getBlockID().equals(bdll4.getBlockIDByCharID("1-3")));
    }

    private static void testMergeBlocks() {
        // Basic merge: second block's content appears in first
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("hello"), "ROOT"));
        bdll.insert(new BlockNode(1, 1, 2, makeCharDLL("world"), "1-0"));
        bdll.mergeBlocks("1-0", "1-1");
        String text = bdll.collectText();
        check("Merged block contains text from both original blocks",
                text.contains("hello") && text.contains("world"));
        check("Second block is marked deleted after merge", bdll.getBlock("1-1").isDeleted());

        // Merge with non-existent first block is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(1, 0, 1, makeCharDLL("only"), "ROOT"));
        try {
            bdll2.mergeBlocks("nonexistent", "1-0");
            check("mergeBlocks with unknown first block ID is silently ignored", true);
        } catch (Exception e) {
            check("mergeBlocks with unknown first block ID is silently ignored", false);
        }

        // Merge with non-existent second block is silently ignored
        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("only"), "ROOT"));
        try {
            bdll3.mergeBlocks("1-0", "nonexistent");
            check("mergeBlocks with unknown second block ID is silently ignored", true);
        } catch (Exception e) {
            check("mergeBlocks with unknown second block ID is silently ignored", false);
        }

        // Merge where first is deleted is skipped
        BlockDLL bdll4 = new BlockDLL();
        bdll4.insert(new BlockNode(1, 0, 1, makeCharDLL("a"), "ROOT"));
        bdll4.insert(new BlockNode(1, 1, 2, makeCharDLL("b"), "1-0"));
        bdll4.delete("1-0");
        bdll4.mergeBlocks("1-0", "1-1");
        check("mergeBlocks skips merge if first block is deleted",
                !bdll4.getBlock("1-1").isDeleted()); // second should NOT be deleted

        // charBlockMap is updated for chars that moved to first block
        BlockDLL bdll5 = new BlockDLL();
        CharDLL c1 = new CharDLL(0, 0, 0);
        c1.insert(new CharNode(1, 0, 1, 'A', "0-0"));
        CharDLL c2 = new CharDLL(0, 0, 0);
        c2.insert(new CharNode(2, 0, 1, 'B', "0-0"));
        bdll5.insert(new BlockNode(1, 0, 1, c1, "ROOT"));
        bdll5.insert(new BlockNode(1, 1, 2, c2, "1-0"));
        bdll5.mergeBlocks("1-0", "1-1");
        check("charBlockMap updated so moved char points to first block",
                "1-0".equals(bdll5.getBlockIDByCharID("2-0")));
    }

    private static void testMoveBlock() {
        // Move a block to a new position
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("A"), "ROOT"));
        bdll.insert(new BlockNode(1, 1, 2, makeCharDLL("B"), "1-0"));
        bdll.insert(new BlockNode(1, 2, 3, makeCharDLL("C"), "1-1"));
        // Current order: A -> B -> C
        // Move B to after C (target = "1-2")
        bdll.moveBlock("1-1", "1-2");
        String text = bdll.collectText();
        int posA = text.indexOf('A');
        int posC = text.indexOf('C');
        int posB = text.lastIndexOf('B');
        check("Block B appears after C after move", posA < posC && posC < posB);

        // Move with unknown block ID is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(1, 0, 1, makeCharDLL("X"), "ROOT"));
        try {
            bdll2.moveBlock("nonexistent", "1-0");
            check("moveBlock with unknown block ID is silently ignored", true);
        } catch (Exception e) {
            check("moveBlock with unknown block ID is silently ignored", false);
        }

        // Move with unknown target ID is silently ignored
        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("X"), "ROOT"));
        try {
            bdll3.moveBlock("1-0", "nonexistent");
            check("moveBlock with unknown target ID is silently ignored", true);
        } catch (Exception e) {
            check("moveBlock with unknown target ID is silently ignored", false);
        }

        // After move, depth is updated correctly (child of target)
        BlockDLL bdll4 = new BlockDLL();
        bdll4.insert(new BlockNode(1, 0, 1, makeCharDLL("P"), "ROOT"));
        bdll4.insert(new BlockNode(1, 1, 2, makeCharDLL("Q"), "ROOT"));
        bdll4.moveBlock("1-1", "1-0");
        check("Moved block depth is target.depth + 1", bdll4.getBlock("1-1").getDepth() == bdll4.getBlock("1-0").getDepth() + 1);

        // After move, parentID is updated to target
        check("Moved block parentID is updated to target block ID",
                "1-0".equals(bdll4.getBlock("1-1").getParentID()));
    }

    private static void testCopyAndPasteBlock() {
        // copyBlock returns a CharDLL with the same text
        // Use a high starting clock (1000) to ensure clone-sentinel "2-1000" != first copied node "2-1001"
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("copy me"), "ROOT"));
        CharDLL copied = bdll.copyBlock("1-0", 2, 1000, 2);
        check("copyBlock returns non-null CharDLL", copied != null);
        check("copyBlock returns CharDLL with same text", copied.collectText().equals("copy me"));

        // copyBlock on non-existent block returns null
        BlockDLL bdll2 = new BlockDLL();
        check("copyBlock on non-existent block returns null",
                bdll2.copyBlock("nonexistent", 1, 0, 1) == null);

        // copyBlock on deleted block returns null
        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("gone"), "ROOT"));
        bdll3.delete("1-0");
        check("copyBlock on deleted block returns null",
                bdll3.copyBlock("1-0", 1, 1, 1) == null);

        // pasteBlock creates a new block after the target
        BlockDLL bdll4 = new BlockDLL();
        CharDLL content = makeCharDLL("original");
        bdll4.insert(new BlockNode(1, 0, 1, content, "ROOT"));
        CharDLL toPaste = makeCharDLL("pasted");
        bdll4.pasteBlock(toPaste, "1-0", 2, 1000, 2);
        check("pasteBlock produces output containing the pasted text",
                bdll4.collectText().contains("pasted"));

        // pasteBlock with null content is silently ignored
        BlockDLL bdll5 = new BlockDLL();
        bdll5.insert(new BlockNode(1, 0, 1, makeCharDLL("safe"), "ROOT"));
        try {
            bdll5.pasteBlock(null, "1-0", 2, 0, 2);
            check("pasteBlock with null CharDLL is silently ignored", true);
        } catch (Exception e) {
            check("pasteBlock with null CharDLL is silently ignored", false);
        }

        // pasteBlock on non-existent target is silently ignored
        BlockDLL bdll6 = new BlockDLL();
        try {
            bdll6.pasteBlock(makeCharDLL("x"), "nonexistent", 1, 0, 1);
            check("pasteBlock on non-existent target is silently ignored", true);
        } catch (Exception e) {
            check("pasteBlock on non-existent target is silently ignored", false);
        }

        // pasteBlock on deleted target is silently ignored
        BlockDLL bdll7 = new BlockDLL();
        bdll7.insert(new BlockNode(1, 0, 1, makeCharDLL("del"), "ROOT"));
        bdll7.delete("1-0");
        try {
            bdll7.pasteBlock(makeCharDLL("x"), "1-0", 1, 1, 1);
            check("pasteBlock on deleted target is silently ignored", true);
        } catch (Exception e) {
            check("pasteBlock on deleted target is silently ignored", false);
        }
    }

    private static void testInsertChar() {
        // insertChar places a new char inside the correct block
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        cdll.insert(new CharNode(1, 0, 1, 'H', "0-0"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        CharNode newChar = new CharNode(1, 1, 2, 'i', "1-0");
        bdll.insertChar("1-0", newChar);
        check("insertChar adds character to the correct block",
                bdll.getBlock("1-0").getContent().collectText().contains("i"));

        // insertChar updates charBlockMap for the new char
        check("insertChar updates charBlockMap for newly inserted char",
                "1-0".equals(bdll.getBlockIDByCharID("1-1")));

        // insertChar with unknown parentCharID is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.insertChar("unknown-char", new CharNode(1, 0, 1, 'z', "unknown-char"));
            check("insertChar with unknown parentCharID is silently ignored", true);
        } catch (Exception e) {
            check("insertChar with unknown parentCharID is silently ignored", false);
        }
    }

    private static void testDeleteChar() {
        // deleteChar removes the char from the block's content
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        cdll.insert(new CharNode(1, 0, 1, 'A', "0-0"));
        cdll.insert(new CharNode(1, 1, 2, 'B', "1-0"));
        cdll.insert(new CharNode(1, 2, 3, '\n', "1-1"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));
        bdll.deleteChar("1-0", 1, 10, 10);
        check("deleteChar marks the character as deleted in the block's content",
                !bdll.getBlock("1-0").getContent().collectText().contains("A"));

        // deleteChar with unknown charID is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.deleteChar("nonexistent-char", 1, 0, 1);
            check("deleteChar with unknown charID is silently ignored", true);
        } catch (Exception e) {
            check("deleteChar with unknown charID is silently ignored", false);
        }
    }

    private static void testReplaceChar() {
        // replaceChar substitutes the old char with the new one
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        cdll.insert(new CharNode(1, 0, 1, 'X', "0-0"));
        cdll.insert(new CharNode(1, 1, 2, '\n', "1-0"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));
        CharNode replacement = new CharNode(2, 0, 3, 'Y', "1-0");
        bdll.replaceChar("1-0", replacement, 1, 5, 5);
        String text = bdll.getBlock("1-0").getContent().collectText();
        check("replaceChar removes old character", !text.contains("X"));
        check("replaceChar inserts new character", text.contains("Y"));

        // replaceChar with unknown old ID is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.replaceChar("unknown", new CharNode(1, 0, 1, 'Z', "unknown"), 1, 0, 1);
            check("replaceChar with unknown old charID is silently ignored", true);
        } catch (Exception e) {
            check("replaceChar with unknown old charID is silently ignored", false);
        }
    }

    private static void testAutosplit() {
        // A block with more than 10 lines should be split automatically
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = makeMultilineDLL(12); // 12 newline chars = 12 lines
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));
        bdll.autosplit(1, 10, 10, "1-0");
        // After autosplit the original block should have at most 10 lines
        check("autosplit reduces original block to <= 10 lines",
                bdll.getBlock("1-0").getContent().getLineCount() <= 10);

        // Autosplit on non-existent block is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.autosplit(1, 0, 1, "nonexistent");
            check("autosplit on non-existent block is silently ignored", true);
        } catch (Exception e) {
            check("autosplit on non-existent block is silently ignored", false);
        }

        // Autosplit on a block with <= 10 lines is a no-op
        BlockDLL bdll3 = new BlockDLL();
        CharDLL cdll3 = makeMultilineDLL(5);
        BlockNode bn3 = new BlockNode(1, 0, 1, cdll3, "ROOT");
        bdll3.insert(bn3);
        bdll3.autosplit(1, 10, 10, "1-0");
        check("autosplit is a no-op when block has <= 10 lines",
                bdll3.getBlock("1-0").getContent().getLineCount() == 5);
    }

    private static void testAutomerge() {
        // A block with < 2 lines should be merged with an adjacent block
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeMultilineDLL(5), "ROOT"));       // 5 lines
        bdll.insert(new BlockNode(1, 1, 2, makeNewlineDLL(), "1-0"));           // 1 line
        bdll.automerge("1-1", 1, 10, 10);
        check("automerge merges a block with < 2 lines into adjacent block",
                bdll.getBlock("1-1").isDeleted());

        // Automerge on non-existent block is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.automerge("nonexistent", 1, 0, 1);
            check("automerge on non-existent block is silently ignored", true);
        } catch (Exception e) {
            check("automerge on non-existent block is silently ignored", false);
        }

        // Block with >= 2 lines is untouched by automerge
        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeMultilineDLL(3), "ROOT"));
        bdll3.automerge("1-0", 1, 0, 1);
        check("automerge is a no-op when block has >= 2 lines",
                !bdll3.getBlock("1-0").isDeleted());
    }

    private static void testCopyBlockContent() {
        // Build an isolated CharDLL with explicit unique IDs (siteID=5 avoids all helper siteIDs)
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(5, 0, 0); // sentinel = "5-0"
        cdll.insert(new CharNode(5, 1, 1, 'A', "5-0"));
        cdll.insert(new CharNode(5, 2, 2, 'B', "5-1"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        // Use a high starting clock so clone sentinel "6-1000" != first node "6-1001"
        CharDLL copied = bdll.copyBlockContent("1-0", 6, 1000, 2, null);
        check("copyBlockContent with null startCharID copies from head",
                copied != null && copied.collectText().equals("AB"));

        // copyBlockContent from a specific char copies from that char onwards
        CharDLL copied2 = bdll.copyBlockContent("1-0", 6, 2000, 2, "5-2");
        check("copyBlockContent with startCharID copies from that char onwards",
                copied2 != null && copied2.collectText().equals("B"));

        // copyBlockContent on non-existent block returns null
        check("copyBlockContent on non-existent block returns null",
                bdll.copyBlockContent("nonexistent", 1, 0, 1, null) == null);

        // copyBlockContent on deleted block returns null
        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(1, 0, 1, makeCharDLL("del"), "ROOT"));
        bdll2.delete("1-0");
        check("copyBlockContent on deleted block returns null",
                bdll2.copyBlockContent("1-0", 1, 1000, 1, null) == null);
    }

    private static void testPasteBlockContent() {
        // pasteBlockContent inserts copied content at the given char position
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(5, 0, 0); // sentinel = "5-0"
        cdll.insert(new CharNode(5, 1, 1, 'A', "5-0"));
        cdll.insert(new CharNode(5, 2, 2, '\n', "5-1"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        CharDLL toPaste = makeCharDLL("XY"); // unique IDs via globalClock
        // Use high clock so clone sentinel doesn't collide with first pasted node
        bdll.pasteBlockContent(6, 1000, 2, "1-0", "5-2", toPaste);
        String text = bdll.collectText();
        check("pasteBlockContent inserts pasted content into block output",
                text.contains("X") && text.contains("Y"));
    }

    private static void testOrdering() {
        // Higher timestamp wins (appears first among siblings)
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("early"), "ROOT"));
        bdll.insert(new BlockNode(2, 0, 3, makeCharDLL("late"), "ROOT"));
        String text = bdll.collectText();
        check("Block with higher timestamp is placed before block with lower timestamp",
                text.indexOf("late") < text.indexOf("early"));

        // Equal timestamps: lower siteID wins
        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(3, 0, 5, makeCharDLL("siteHigh"), "ROOT"));
        bdll2.insert(new BlockNode(1, 1, 5, makeCharDLL("siteLow"), "ROOT"));
        String text2 = bdll2.collectText();
        check("When timestamps equal, block with lower siteID wins (appears first)",
                text2.indexOf("siteLow") < text2.indexOf("siteHigh"));

        // Insertion order does not affect final ordering (convergence)
        BlockDLL bdllA = new BlockDLL();
        bdllA.insert(new BlockNode(1, 0, 1, makeCharDLL("P"), "ROOT"));
        bdllA.insert(new BlockNode(2, 0, 2, makeCharDLL("Q"), "ROOT"));
        bdllA.insert(new BlockNode(3, 0, 3, makeCharDLL("R"), "ROOT"));

        BlockDLL bdllB = new BlockDLL();
        bdllB.insert(new BlockNode(3, 0, 3, makeCharDLL("R"), "ROOT"));
        bdllB.insert(new BlockNode(1, 0, 1, makeCharDLL("P"), "ROOT"));
        bdllB.insert(new BlockNode(2, 0, 2, makeCharDLL("Q"), "ROOT"));
        check("Same blocks inserted in different order converge to the same text",
                bdllA.collectText().equals(bdllB.collectText()));
    }

    private static void testEdgeCases() {
        // collectText skips deleted blocks but continues to remaining blocks
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("keep"), "ROOT"));
        bdll.insert(new BlockNode(1, 1, 2, makeCharDLL("remove"), "1-0"));
        bdll.insert(new BlockNode(1, 2, 3, makeCharDLL("also keep"), "1-1"));
        bdll.delete("1-1");
        String text = bdll.collectText();
        check("collectText skips deleted blocks but includes remaining ones",
                text.contains("keep") && !text.contains("remove") && text.contains("also keep"));

        // A block with no lines (empty CharDLL) is merged/removed by automerge
        BlockDLL bdll2 = new BlockDLL();
        CharDLL empty = new CharDLL(0, 0, 0);  // 0 lines
        CharDLL neighbor = makeMultilineDLL(5);
        bdll2.insert(new BlockNode(1, 0, 1, neighbor, "ROOT"));
        bdll2.insert(new BlockNode(1, 1, 2, empty, "1-0"));
        bdll2.automerge("1-1", 1, 10, 10);
        check("Block with 0 lines is merged away by automerge",
                bdll2.getBlock("1-1").isDeleted());

        // getBlock on ROOT sentinel
        BlockDLL bdll3 = new BlockDLL();
        check("getBlock(\"ROOT\") returns the sentinel head node",
                bdll3.getBlock("ROOT") != null);

        // Inserting a block whose blockID equals ROOT is rejected
        BlockDLL bdll4 = new BlockDLL();
        // ROOT is already in the map; a second insert with blockID "ROOT" should be ignored
        // We can't directly construct a BlockNode with blockID "ROOT" through the normal constructor
        // (it generates siteID+"-"+clock), so we verify the sentinel is undamaged after any attempt:
        check("ROOT sentinel is always present and undamaged",
                bdll4.getBlock("ROOT") != null && !bdll4.getBlock("ROOT").isDeleted());

        // Large sequence: 50 blocks inserted sequentially, all visible
        BlockDLL bdll5 = new BlockDLL();
        String prevParent = "ROOT";
        for (int i = 0; i < 50; i++) {
            BlockNode bn = new BlockNode(1, i, i + 1, makeNewlineDLL(), prevParent);
            bdll5.insert(bn);
            prevParent = "1-" + i;
        }
        // Count non-deleted blocks
        int count = 0;
        BlockNode ptr = bdll5.getBlock("ROOT").getNext();
        while (ptr != null) { if (!ptr.isDeleted()) count++; ptr = ptr.getNext(); }
        check("50 sequentially inserted blocks are all present and non-deleted", count == 50);
    }
}