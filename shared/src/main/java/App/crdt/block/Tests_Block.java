package App.crdt.block;

import App.crdt.character.CharDLL;
import App.crdt.character.CharNode;

import static App.crdt.character.CharDLL.convertJSONToCharDLL;

public class Tests_Block {
    private static int passes = 0;
    private static int failures = 0;

    public static void main() {
       /* testBasicInsertAndCollect();
        testDeleteBlock();
        testSplitBlock();
        testMergeBlocks();
        testMoveBlock();
//        testCopyAndPasteBlock();
        testInsertChar();
        testDeleteChar();
        testReplaceChar();
        testAutosplit();
        testAutomerge();*/
        testCopyBlockContent();
        testPasteBlockContent();
//        testOrdering();
//        testEdgeCases();
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

    private static long globalClock = 1;

    private static CharDLL makeCharDLL(String text) {
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

    private static CharDLL makeNewlineDLL() {
        long headClock = globalClock++;
        CharDLL dll = new CharDLL(0, headClock, 0);
        long c = globalClock++;
        dll.insert(new CharNode(8, c, 1, '\n', "0-" + headClock));
        return dll;
    }

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
        BlockDLL bdll = new BlockDLL();
        check("Empty BlockDLL produces empty text", bdll.collectText().isEmpty());

        BlockDLL bdll2 = new BlockDLL();
        CharDLL content = makeCharDLL("hello");
        BlockNode b = new BlockNode(1, 0, 1, content, "ROOT");
        bdll2.insert(b);
        check("Single block inserted produces its text", bdll2.collectText().equals("hello"));

        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("first"), "ROOT"));
        bdll3.insert(new BlockNode(1, 1, 2, makeCharDLL("second"), "1-0"));
        String text3 = bdll3.collectText();
        check("Two sequential blocks both appear in output", text3.contains("first") && text3.contains("second"));

        BlockDLL bdll4 = new BlockDLL();
        BlockNode dup = new BlockNode(1, 0, 1, makeCharDLL("dup"), "ROOT");
        bdll4.insert(dup);
        bdll4.insert(dup);
        check("Duplicate block insert is silently ignored", bdll4.collectText().equals("dup"));

        BlockDLL bdll5 = new BlockDLL();
        bdll5.insert(new BlockNode(1, 0, 1, makeCharDLL("orphan"), "nonexistent-parent"));
        check("Insert with unknown parent is silently ignored", bdll5.collectText().isEmpty());

        BlockDLL bdll6 = new BlockDLL();
        BlockNode target = new BlockNode(2, 5, 1, makeCharDLL("find me"), "ROOT");
        bdll6.insert(target);
        check("getBlock returns the correct block by ID", bdll6.getBlock("2-5") == target);
        check("getBlock returns null for unknown ID", bdll6.getBlock("99-99") == null);

        BlockDLL bdll7 = new BlockDLL();
        CharDLL cdll7 = new CharDLL(0, 0, 0);
        cdll7.insert(new CharNode(3, 0, 1, 'x', "0-0"));
        bdll7.insert(new BlockNode(1, 0, 1, cdll7, "ROOT"));
        check("getBlockIDByCharID returns block ID for a char inside it",
                "1-0".equals(bdll7.getBlockIDByCharID("3-0")));
    }

    private static void testDeleteBlock() {
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("visible"), "ROOT"));
        bdll.delete("1-0");
        check("Deleted block is excluded from collected text", bdll.collectText().isEmpty());

        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(1, 0, 1, makeCharDLL("hi"), "ROOT"));
        bdll2.delete("1-0");
        try {
            bdll2.delete("1-0");
            check("Double delete is silently ignored", true);
        } catch (Exception e) {
            check("Double delete is silently ignored", false);
        }

        BlockDLL bdll3 = new BlockDLL();
        try {
            bdll3.delete("fake-99");
            check("Delete of non-existent block ID is silently ignored", true);
        } catch (Exception e) {
            check("Delete of non-existent block ID is silently ignored", false);
        }

        BlockDLL bdll4 = new BlockDLL();
        BlockNode bn = new BlockNode(1, 0, 1, makeCharDLL("mark"), "ROOT");
        bdll4.insert(bn);
        bdll4.delete("1-0");
        check("Deleted block has isDeleted() == true", bn.isDeleted());
    }

    private static void testSplitBlock() {
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        cdll.insert(new CharNode(1, 0, 1, 'A', "0-0"));
        cdll.insert(new CharNode(1, 1, 2, 'B', "1-0"));
        cdll.insert(new CharNode(1, 2, 3, '\n', "1-1"));
        cdll.insert(new CharNode(1, 3, 4, 'C', "1-2"));
        cdll.insert(new CharNode(1, 4, 5, 'D', "1-3"));
        cdll.insert(new CharNode(1, 5, 6, '\n', "1-4"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        BlockNode newBlock = bdll.splitBlock(2, 0, 2, "1-3");
        check("splitBlock returns a non-null new block", newBlock != null);
        check("splitBlock: new block contains tail characters",
                newBlock.getContent().collectText().contains("D"));

        BlockDLL bdll2 = new BlockDLL();
        BlockNode result = bdll2.splitBlock(1, 0, 1, "nonexistent-char");
        check("splitBlock with unknown charID returns null", result == null);

        BlockDLL bdll3 = new BlockDLL();
        CharDLL cdll3 = new CharDLL(0, 0, 0);
        cdll3.insert(new CharNode(1, 0, 1, 'a', "0-0"));
        cdll3.insert(new CharNode(1, 1, 2, '\n', "1-0"));
        bdll3.insert(new BlockNode(1, 0, 1, cdll3, "ROOT"));
        bdll3.delete("1-0");
        BlockNode result3 = bdll3.splitBlock(1, 1, 1, "1-0");
        check("splitBlock on deleted block returns null", result3 == null);

        BlockDLL bdll4 = new BlockDLL();
        CharDLL cdll4 = new CharDLL(0, 0, 0);
        cdll4.insert(new CharNode(1, 0, 1, 'X', "0-0"));
        cdll4.insert(new CharNode(1, 1, 2, 'Y', "1-0"));
        cdll4.insert(new CharNode(1, 2, 3, '\n', "1-1"));
        cdll4.insert(new CharNode(1, 3, 4, 'Z', "1-2"));
        cdll4.insert(new CharNode(1, 4, 5, '\n', "1-3"));
        bdll4.insert(new BlockNode(1, 0, 1, cdll4, "ROOT"));
        BlockNode splitResult = bdll4.splitBlock(2, 0, 2, "1-3");
        check("splitBlock updates charBlockMap for chars moved to new block",
                splitResult != null && splitResult.getBlockID().equals(bdll4.getBlockIDByCharID("1-3")));
    }

    private static void testMergeBlocks() {
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("hello"), "ROOT"));
        bdll.insert(new BlockNode(1, 1, 2, makeCharDLL("world"), "1-0"));
        bdll.mergeBlocks("1-0", "1-1");
        String text = bdll.collectText();
        check("Merged block contains text from both original blocks",
                text.contains("hello") && text.contains("world"));
        check("Second block is marked deleted after merge", bdll.getBlock("1-1").isDeleted());

        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(1, 0, 1, makeCharDLL("only"), "ROOT"));
        try {
            bdll2.mergeBlocks("nonexistent", "1-0");
            check("mergeBlocks with unknown first block ID is silently ignored", true);
        } catch (Exception e) {
            check("mergeBlocks with unknown first block ID is silently ignored", false);
        }

        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("only"), "ROOT"));
        try {
            bdll3.mergeBlocks("1-0", "nonexistent");
            check("mergeBlocks with unknown second block ID is silently ignored", true);
        } catch (Exception e) {
            check("mergeBlocks with unknown second block ID is silently ignored", false);
        }

        BlockDLL bdll4 = new BlockDLL();
        bdll4.insert(new BlockNode(1, 0, 1, makeCharDLL("a"), "ROOT"));
        bdll4.insert(new BlockNode(1, 1, 2, makeCharDLL("b"), "1-0"));
        bdll4.delete("1-0");
        bdll4.mergeBlocks("1-0", "1-1");
        check("mergeBlocks skips merge if first block is deleted",
                !bdll4.getBlock("1-1").isDeleted());

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
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("A"), "ROOT"));
        bdll.insert(new BlockNode(1, 1, 2, makeCharDLL("B"), "1-0"));
        bdll.insert(new BlockNode(1, 2, 3, makeCharDLL("C"), "1-1"));
        bdll.moveBlock("1-1", "1-2");
        String text = bdll.collectText();
        int posA = text.indexOf('A');
        int posC = text.indexOf('C');
        int posB = text.lastIndexOf('B');
        check("Block B appears after C after move", posA < posC && posC < posB);

        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(1, 0, 1, makeCharDLL("X"), "ROOT"));
        try {
            bdll2.moveBlock("nonexistent", "1-0");
            check("moveBlock with unknown block ID is silently ignored", true);
        } catch (Exception e) {
            check("moveBlock with unknown block ID is silently ignored", false);
        }

        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("X"), "ROOT"));
        try {
            bdll3.moveBlock("1-0", "nonexistent");
            check("moveBlock with unknown target ID is silently ignored", true);
        } catch (Exception e) {
            check("moveBlock with unknown target ID is silently ignored", false);
        }

        BlockDLL bdll4 = new BlockDLL();
        bdll4.insert(new BlockNode(1, 0, 1, makeCharDLL("P"), "ROOT"));
        bdll4.insert(new BlockNode(1, 1, 2, makeCharDLL("Q"), "ROOT"));
        bdll4.moveBlock("1-1", "1-0");
        check("Moved block depth is target.depth + 1",
                bdll4.getBlock("1-1").getDepth() == bdll4.getBlock("1-0").getDepth() + 1);
        check("Moved block parentID is updated to target block ID",
                "1-0".equals(bdll4.getBlock("1-1").getParentID()));
    }

   /* private static void testCopyAndPasteBlock() {
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("copy me"), "ROOT"));
//        String copied = bdll.copyBlock("1-0", 2, 1000, 2);
        check("copyBlock returns non-null CharDLL", copied != null);
        check("copyBlock returns CharDLL with same text", copied.collectText().equals("copy me"));

        BlockDLL bdll2 = new BlockDLL();
        check("copyBlock on non-existent block returns null",
//                bdll2.copyBlock("nonexistent", 1, 0, 1) == null);

//        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeCharDLL("gone"), "ROOT"));
        bdll3.delete("1-0");
        check("copyBlock on deleted block returns null",
                bdll3.copyBlock("1-0", 1, 1, 1) == null);

        BlockDLL bdll4 = new BlockDLL();
        CharDLL content = makeCharDLL("original");
        bdll4.insert(new BlockNode(1, 0, 1, content, "ROOT"));
        CharDLL toPaste = makeCharDLL("pasted");
        bdll4.pasteBlock(toPaste, "1-0", 2, 1000, 2);
        check("pasteBlock produces output containing the pasted text",
                bdll4.collectText().contains("pasted"));

        BlockDLL bdll5 = new BlockDLL();
        bdll5.insert(new BlockNode(1, 0, 1, makeCharDLL("safe"), "ROOT"));
        try {
            bdll5.pasteBlock(null, "1-0", 2, 0, 2);
            check("pasteBlock with null CharDLL is silently ignored", true);
        } catch (Exception e) {
            check("pasteBlock with null CharDLL is silently ignored", false);
        }

        BlockDLL bdll6 = new BlockDLL();
        try {
            bdll6.pasteBlock(makeCharDLL("x"), "nonexistent", 1, 0, 1);
            check("pasteBlock on non-existent target is silently ignored", true);
        } catch (Exception e) {
            check("pasteBlock on non-existent target is silently ignored", false);
        }

        BlockDLL bdll7 = new BlockDLL();
        bdll7.insert(new BlockNode(1, 0, 1, makeCharDLL("del"), "ROOT"));
        bdll7.delete("1-0");
        try {
            bdll7.pasteBlock(makeCharDLL("x"), "1-0", 1, 1, 1);
            check("pasteBlock on deleted target is silently ignored", true);
        } catch (Exception e) {
            check("pasteBlock on deleted target is silently ignored", false);
        }
    } */

    private static void testInsertChar() {
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        cdll.insert(new CharNode(1, 0, 1, 'H', "0-0"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        CharNode newChar = new CharNode(1, 1, 2, 'i', "1-0");
        bdll.insertChar("1-0", newChar);
        check("insertChar adds character to the correct block",
                bdll.getBlock("1-0").getContent().collectText().contains("i"));
        check("insertChar updates charBlockMap for newly inserted char",
                "1-0".equals(bdll.getBlockIDByCharID("1-1")));

        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.insertChar("unknown-char", new CharNode(1, 0, 1, 'z', "unknown-char"));
            check("insertChar with unknown parentCharID is silently ignored", true);
        } catch (Exception e) {
            check("insertChar with unknown parentCharID is silently ignored", false);
        }
    }

    private static void testDeleteChar() {
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        cdll.insert(new CharNode(1, 0, 1, 'A', "0-0"));
        cdll.insert(new CharNode(1, 1, 2, 'B', "1-0"));
        cdll.insert(new CharNode(1, 2, 3, '\n', "1-1"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));
//        bdll.deleteChar("1-0", 1, 10, 10);
        check("deleteChar marks the character as deleted in the block's content",
                !bdll.getBlock("1-0").getContent().collectText().contains("A"));

        BlockDLL bdll2 = new BlockDLL();
        try {
//            bdll2.deleteChar("nonexistent-char", 1, 0, 1);
            check("deleteChar with unknown charID is silently ignored", true);
        } catch (Exception e) {
            check("deleteChar with unknown charID is silently ignored", false);
        }
    }

    private static void testReplaceChar() {
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(0, 0, 0);
        cdll.insert(new CharNode(1, 0, 1, 'X', "0-0"));
        cdll.insert(new CharNode(1, 1, 2, '\n', "1-0"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));
        CharNode replacement = new CharNode(2, 0, 3, 'Y', "1-0");
//        bdll.replaceChar("1-0", replacement, 1, 5, 5);
        String text = bdll.getBlock("1-0").getContent().collectText();
        check("replaceChar removes old character", !text.contains("X"));
        check("replaceChar inserts new character", text.contains("Y"));

        BlockDLL bdll2 = new BlockDLL();
        try {
//            bdll2.replaceChar("unknown", new CharNode(1, 0, 1, 'Z', "unknown"), 1, 0, 1);
            check("replaceChar with unknown old charID is silently ignored", true);
        } catch (Exception e) {
            check("replaceChar with unknown old charID is silently ignored", false);
        }
    }

    private static void testAutosplit() {
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = makeMultilineDLL(12);
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));
        bdll.autosplit(1, 10, 10, "1-0");
        check("autosplit reduces original block to <= 10 lines",
                bdll.getBlock("1-0").getContent().getLineCount() <= 10);

        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.autosplit(1, 0, 1, "nonexistent");
            check("autosplit on non-existent block is silently ignored", true);
        } catch (Exception e) {
            check("autosplit on non-existent block is silently ignored", false);
        }

        BlockDLL bdll3 = new BlockDLL();
        CharDLL cdll3 = makeMultilineDLL(5);
        BlockNode bn3 = new BlockNode(1, 0, 1, cdll3, "ROOT");
        bdll3.insert(bn3);
        bdll3.autosplit(1, 10, 10, "1-0");
        check("autosplit is a no-op when block has <= 10 lines",
                bdll3.getBlock("1-0").getContent().getLineCount() == 5);
    }

    private static void testAutomerge() {
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeMultilineDLL(5), "ROOT"));
        bdll.insert(new BlockNode(1, 1, 2, makeNewlineDLL(), "1-0"));
        bdll.automerge("1-1", 1, 10, 10);
        check("automerge merges a block with < 2 lines into adjacent block",
                bdll.getBlock("1-1").isDeleted());

        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.automerge("nonexistent", 1, 0, 1);
            check("automerge on non-existent block is silently ignored", true);
        } catch (Exception e) {
            check("automerge on non-existent block is silently ignored", false);
        }

        BlockDLL bdll3 = new BlockDLL();
        bdll3.insert(new BlockNode(1, 0, 1, makeMultilineDLL(3), "ROOT"));
        bdll3.automerge("1-0", 1, 0, 1);
        check("automerge is a no-op when block has >= 2 lines",
                !bdll3.getBlock("1-0").isDeleted());
    }

    private static void testCopyBlockContent() {
        // copyBlockContent(siteID, clock, time, startCharID, endCharID)
        // no longer takes blockID — derived from charBlockMap internally
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(5, 0, 0); // sentinel = "5-0"
        cdll.insert(new CharNode(5, 1, 1, 'A', "5-0"));
        cdll.insert(new CharNode(5, 2, 2, 'B', "5-1"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        // Copy from first char to end (endCharID = null)
        String copied = bdll.copyBlockContent(6, 1000, 2, "5-1", null);
        check("copyBlockContent copies from startCharID to end when endCharID is null",
                copied != null && convertJSONToCharDLL(copied,1,0,1).collectText().equals("AB"));

        // Copy from second char to end
        String copied2 = bdll.copyBlockContent(6, 2000, 2, "5-2", null);
        check("copyBlockContent with startCharID copies from that char to end",
                copied2 != null && convertJSONToCharDLL(copied2,1,0,1).collectText().equals("B"));

        // Copy inclusive range: just 'A'
        String copied3 = bdll.copyBlockContent(6, 3000, 2, "5-1", "5-1");
        check("copyBlockContent with startCharID and endCharID copies inclusive range",
                copied3 != null && convertJSONToCharDLL(copied3,1,0,1).collectText().equals("A"));

        // Unknown startCharID returns null
        check("copyBlockContent with unknown startCharID returns null",
                bdll.copyBlockContent(1, 0, 1, "nonexistent", null) == null);

        // Copy spanning two blocks
        BlockDLL bdll2 = new BlockDLL();
        CharDLL cdll2a = new CharDLL(5, 20, 0); // sentinel = "5-20"
        cdll2a.insert(new CharNode(5, 21, 1, 'A', "5-20"));
        cdll2a.insert(new CharNode(5, 22, 2, 'B', "5-21"));
        CharDLL cdll2b = new CharDLL(5, 30, 0); // sentinel = "5-30"
        cdll2b.insert(new CharNode(5, 31, 1, 'C', "5-30"));
        cdll2b.insert(new CharNode(5, 32, 2, 'D', "5-31"));
        bdll2.insert(new BlockNode(1, 0, 1, cdll2a, "ROOT"));
        bdll2.insert(new BlockNode(1, 1, 2, cdll2b, "1-0"));
        // Copy from 'B' in block 1 to 'C' in block 2
        String multiCopy = bdll2.copyBlockContent(6, 4000, 2, "5-22", "5-31");
        check("copyBlockContent spanning two blocks copies correct content",
                multiCopy != null && convertJSONToCharDLL(multiCopy,1,0,1).collectText().equals("BC"));

        // Copy from start of first block to end of document (endCharID = null)
        String fullCopy = bdll2.copyBlockContent(6, 5000, 2, "5-21", null);
        check("copyBlockContent from startCharID to end of document copies all remaining content",
                fullCopy != null && convertJSONToCharDLL(fullCopy,1,0,1).collectText().equals("ABCD"));
    }

    private static void testPasteBlockContent() {
        // pasteBlockContent(siteID, clock, time, charID, copied)
        // no longer takes blockID — derived from charBlockMap via charID
        BlockDLL bdll = new BlockDLL();
        CharDLL cdll = new CharDLL(5, 0, 0); // sentinel = "5-0"
        cdll.insert(new CharNode(5, 1, 1, 'A', "5-0"));
        cdll.insert(new CharNode(5, 2, 2, '\n', "5-1"));
        bdll.insert(new BlockNode(1, 0, 1, cdll, "ROOT"));

        String toPaste = makeCharDLL("XY").convertListToJson();
        bdll.pasteBlockContent(6, 1000, 2, "5-2", toPaste);
        String text = bdll.collectText();
        check("pasteBlockContent inserts pasted content into block output",
                text.contains("X") && text.contains("Y"));

        // Unknown charID is silently ignored
        BlockDLL bdll2 = new BlockDLL();
        try {
            bdll2.pasteBlockContent(1, 0, 1, "nonexistent-char", makeCharDLL("z").convertListToJson());
            check("pasteBlockContent with unknown charID is silently ignored", true);
        } catch (Exception e) {
            check("pasteBlockContent with unknown charID is silently ignored", false);
        }
    }

    private static void testOrdering() {
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("early"), "ROOT"));
        bdll.insert(new BlockNode(2, 0, 3, makeCharDLL("late"), "ROOT"));
        String text = bdll.collectText();
        check("Block with higher timestamp is placed before block with lower timestamp",
                text.indexOf("late") < text.indexOf("early"));

        BlockDLL bdll2 = new BlockDLL();
        bdll2.insert(new BlockNode(3, 0, 5, makeCharDLL("siteHigh"), "ROOT"));
        bdll2.insert(new BlockNode(1, 1, 5, makeCharDLL("siteLow"), "ROOT"));
        String text2 = bdll2.collectText();
        check("When timestamps equal, block with lower siteID wins (appears first)",
                text2.indexOf("siteLow") < text2.indexOf("siteHigh"));

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
        BlockDLL bdll = new BlockDLL();
        bdll.insert(new BlockNode(1, 0, 1, makeCharDLL("keep"), "ROOT"));
        bdll.insert(new BlockNode(1, 1, 2, makeCharDLL("remove"), "1-0"));
        bdll.insert(new BlockNode(1, 2, 3, makeCharDLL("also keep"), "1-1"));
        bdll.delete("1-1");
        String text = bdll.collectText();
        check("collectText skips deleted blocks but includes remaining ones",
                text.contains("keep") && !text.contains("remove") && text.contains("also keep"));

        BlockDLL bdll2 = new BlockDLL();
        CharDLL empty = new CharDLL(0, 0, 0);
        CharDLL neighbor = makeMultilineDLL(5);
        bdll2.insert(new BlockNode(1, 0, 1, neighbor, "ROOT"));
        bdll2.insert(new BlockNode(1, 1, 2, empty, "1-0"));
        bdll2.automerge("1-1", 1, 10, 10);
        check("Block with 0 lines is merged away by automerge",
                bdll2.getBlock("1-1").isDeleted());

        BlockDLL bdll3 = new BlockDLL();
        check("getBlock(\"ROOT\") returns the sentinel head node",
                bdll3.getBlock("ROOT") != null);

        BlockDLL bdll4 = new BlockDLL();
        check("ROOT sentinel is always present and undamaged",
                bdll4.getBlock("ROOT") != null && !bdll4.getBlock("ROOT").isDeleted());

        BlockDLL bdll5 = new BlockDLL();
        String prevParent = "ROOT";
        for (int i = 0; i < 50; i++) {
            BlockNode bn = new BlockNode(1, i, i + 1, makeNewlineDLL(), prevParent);
            bdll5.insert(bn);
            prevParent = "1-" + i;
        }
        int count = 0;
        BlockNode ptr = bdll5.getBlock("ROOT").getNext();
        while (ptr != null) { if (!ptr.isDeleted()) count++; ptr = ptr.getNext(); }
        check("50 sequentially inserted blocks are all present and non-deleted", count == 50);
    }
}