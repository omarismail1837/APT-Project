package crdt.block;

import crdt.character.CharDLL;
import crdt.character.CharNode;

public class Tests_Block {
    private static int passes = 0;
    private static int failures = 0;

    public static void main() {
        testBasicOperations();
        testOrdering();
        testSplitAndMerge();
        testCharBlockMap();
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

    /**
     * Build a CharDLL with the given string.
     * CharDLL(0,0,0) sentinel charID = "0-0", so first node's parentID = "0-0".
     * Subsequent nodes use the previous node's charID as parentID.
     */
    private static CharDLL makeContent(int siteID, long startClock, String text) {
        CharDLL dll = new CharDLL(0, 0, 0);
        long clock = startClock;
        String prevID = "0-0"; // sentinel charID for CharDLL(0,0,0)
        for (char ch : text.toCharArray()) {
            CharNode node = new CharNode(siteID, clock, 1, ch, prevID);
            dll.insert(node);
            prevID = siteID + "-" + clock;
            clock++;
        }
        return dll;
    }

    // ─── Basic Operations ─────────────────────────────────────────────────────

    private static void testBasicOperations() {

        // Empty BlockDLL returns empty string
        BlockDLL dll = new BlockDLL();
        check("Empty BlockDLL returns empty string", dll.collectText().trim().isEmpty());

        // Single block insert produces correct text
        BlockDLL dll2 = new BlockDLL();
        dll2.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        check("Single block insert produces correct text", dll2.collectText().contains("hello"));

        // Orphan block is silently ignored
        BlockDLL dll3 = new BlockDLL();
        dll3.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "9-9"));
        check("Orphan block insert is silently ignored", dll3.collectText().trim().isEmpty());

        // Duplicate block insert is silently ignored
        BlockDLL dll4 = new BlockDLL();
        BlockNode b = new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT");
        dll4.insert(b);
        dll4.insert(b);
        String t4 = dll4.collectText();
        check("Duplicate block insert is silently ignored",
                t4.indexOf("hello") == t4.lastIndexOf("hello"));

        // Multiple sequential blocks produce correct text
        BlockDLL dll5 = new BlockDLL();
        dll5.insert(new BlockNode(1, 0, 1, makeContent(1, 0,  "hello"), "ROOT"));
        dll5.insert(new BlockNode(1, 1, 2, makeContent(1, 10, "world"), "1-0"));
        String t5 = dll5.collectText();
        check("Sequential block inserts produce correct text",
                t5.contains("hello") && t5.contains("world") && t5.indexOf("hello") < t5.indexOf("world"));

        // Delete block removes it from collected text
        BlockDLL dll6 = new BlockDLL();
        dll6.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        dll6.delete("1-0");
        check("Deleting a block removes it from collected text", !dll6.collectText().contains("hello"));

        // Double delete is silently ignored
        BlockDLL dll7 = new BlockDLL();
        dll7.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        dll7.delete("1-0");
        try {
            dll7.delete("1-0");
            check("Double delete is silently ignored", true);
        } catch (Exception e) {
            check("Double delete is silently ignored", false);
        }

        // Delete of non-existent block is silently ignored
        BlockDLL dll8 = new BlockDLL();
        try {
            dll8.delete("fake-id");
            check("Delete of non-existent block is silently ignored", true);
        } catch (Exception e) {
            check("Delete of non-existent block is silently ignored", false);
        }

        // Deleting all blocks returns empty string
        BlockDLL dll9 = new BlockDLL();
        dll9.insert(new BlockNode(1, 0, 1, makeContent(1, 0,  "hello"), "ROOT"));
        dll9.insert(new BlockNode(1, 1, 2, makeContent(1, 10, "world"), "1-0"));
        dll9.delete("1-0");
        dll9.delete("1-1");
        check("Deleting all blocks returns empty string", dll9.collectText().trim().isEmpty());

        // Child of deleted block is still inserted correctly
        BlockDLL dll10 = new BlockDLL();
        dll10.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        dll10.delete("1-0");
        dll10.insert(new BlockNode(1, 1, 2, makeContent(1, 10, "world"), "1-0"));
        check("Child of deleted block is inserted correctly", dll10.collectText().contains("world"));
    }

    // ─── Ordering ─────────────────────────────────────────────────────────────

    private static void testOrdering() {

        // Higher timestamp placed before lower timestamp sibling
        BlockDLL dll = new BlockDLL();
        dll.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "A"), "ROOT"));
        dll.insert(new BlockNode(2, 0, 2, makeContent(2, 0, "B"), "ROOT"));
        dll.insert(new BlockNode(3, 0, 3, makeContent(3, 0, "C"), "ROOT"));
        String t1 = dll.collectText();
        check("Higher timestamp block placed before lower timestamp sibling",
                t1.indexOf("C") < t1.indexOf("B") && t1.indexOf("B") < t1.indexOf("A"));

        // Lower siteID wins when timestamps are equal
        BlockDLL dll2 = new BlockDLL();
        dll2.insert(new BlockNode(1, 0, 5, makeContent(1, 0, "A"), "ROOT"));
        dll2.insert(new BlockNode(2, 0, 5, makeContent(2, 0, "B"), "ROOT"));
        dll2.insert(new BlockNode(3, 0, 5, makeContent(3, 0, "C"), "ROOT"));
        String t2 = dll2.collectText();
        check("Lower siteID block wins when timestamps are equal",
                t2.indexOf("A") < t2.indexOf("B") && t2.indexOf("B") < t2.indexOf("C"));

        // Same operations in different order converge to same text
        BlockDLL dll3 = new BlockDLL();
        BlockDLL dll4 = new BlockDLL();
        dll3.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "A"), "ROOT"));
        dll3.insert(new BlockNode(2, 0, 2, makeContent(2, 0, "B"), "ROOT"));
        dll3.insert(new BlockNode(3, 0, 3, makeContent(3, 0, "C"), "ROOT"));
        dll4.insert(new BlockNode(3, 0, 3, makeContent(3, 0, "C"), "ROOT"));
        dll4.insert(new BlockNode(2, 0, 2, makeContent(2, 0, "B"), "ROOT"));
        dll4.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "A"), "ROOT"));
        check("Same block operations in different order converge to same text",
                dll3.collectText().equals(dll4.collectText()));

        // Losing sibling placed after winning sibling's descendants
        BlockDLL dll5 = new BlockDLL();
        dll5.insert(new BlockNode(1, 0, 2, makeContent(1, 0,  "Y"), "ROOT"));
        dll5.insert(new BlockNode(1, 1, 3, makeContent(1, 10, "a"), "1-0"));
        dll5.insert(new BlockNode(1, 2, 4, makeContent(1, 20, "s"), "1-1"));
        dll5.insert(new BlockNode(2, 0, 1, makeContent(2, 0,  "N"), "ROOT"));
        String t5 = dll5.collectText();
        check("Losing sibling placed after winning sibling's descendants",
                t5.indexOf("Y") < t5.indexOf("a") &&
                        t5.indexOf("a") < t5.indexOf("s") &&
                        t5.indexOf("s") < t5.indexOf("N"));

        // Sibling that loses to all others placed at end
        BlockDLL dll6 = new BlockDLL();
        dll6.insert(new BlockNode(1, 0, 3, makeContent(1, 0,  "Y"), "ROOT"));
        dll6.insert(new BlockNode(1, 1, 4, makeContent(1, 10, "a"), "1-0"));
        dll6.insert(new BlockNode(2, 0, 5, makeContent(2, 0,  "N"), "ROOT"));
        dll6.insert(new BlockNode(2, 1, 6, makeContent(2, 10, "a"), "2-0"));
        dll6.insert(new BlockNode(3, 0, 1, makeContent(3, 0,  "L"), "ROOT"));
        String t6 = dll6.collectText();
        check("Sibling that loses to all others placed at end",
                t6.indexOf("N") < t6.indexOf("Y") &&
                        t6.indexOf("Y") < t6.indexOf("L"));
    }

    // ─── Split and Merge ──────────────────────────────────────────────────────

    private static void testSplitAndMerge() {

        // Split block produces correct text in both halves
        // makeContent(1, 0, "helloworld") → chars "1-0" to "1-9", first node parentID = "0-0"
        BlockDLL dll = new BlockDLL();
        dll.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "helloworld"), "ROOT"));
        // "1-5" is 'w', the 6th character
        BlockNode second = dll.splitBlock(1, 10, 1, "1-0", "1-5");
        check("Split block: first half correct",
                dll.getBlock("1-0").getContent().collectText().equals("hello"));
        check("Split block: second half correct",
                second != null && second.getContent().collectText().equals("world"));

        // Split produces two blocks in collectText
        BlockDLL dll2 = new BlockDLL();
        dll2.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "helloworld"), "ROOT"));
        dll2.splitBlock(1, 10, 1, "1-0", "1-5");
        String t2 = dll2.collectText();
        check("Split block: both halves appear in collectText",
                t2.contains("hello") && t2.contains("world"));

        // Split on deleted block returns null
        BlockDLL dll3 = new BlockDLL();
        dll3.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        dll3.delete("1-0");
        check("Split on deleted block returns null",
                dll3.splitBlock(1, 10, 1, "1-0", "1-2") == null);

        // Split on non-existent charID returns empty second block
        BlockDLL dll4 = new BlockDLL();
        dll4.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        BlockNode result = dll4.splitBlock(1, 10, 1, "1-0", "9-9");
        check("Split at non-existent charID returns empty second block",
                result != null && result.getContent().collectText().isEmpty());

        // Merge two blocks produces correct combined text
        BlockDLL dll5 = new BlockDLL();
        dll5.insert(new BlockNode(1, 0, 1, makeContent(1, 0,  "hello"), "ROOT"));
        dll5.insert(new BlockNode(1, 1, 2, makeContent(1, 10, "world"), "1-0"));
        dll5.mergeBlocks("1-0", "1-1");
        check("Merge blocks: combined text correct",
                dll5.getBlock("1-0").getContent().collectText().equals("helloworld"));
        check("Merge blocks: second block tombstoned",
                dll5.getBlock("1-1").isDeleted());

        // Split then merge returns original text
        BlockDLL dll6 = new BlockDLL();
        dll6.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "helloworld"), "ROOT"));
        BlockNode half = dll6.splitBlock(1, 10, 1, "1-0", "1-5");
        dll6.mergeBlocks("1-0", half.getBlockID());
        check("Split then merge returns original text",
                dll6.getBlock("1-0").getContent().collectText().equals("helloworld"));

        // Merge with non-existent block is silently ignored
        BlockDLL dll7 = new BlockDLL();
        dll7.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        try {
            dll7.mergeBlocks("1-0", "fake-id");
            check("Merge with non-existent block is silently ignored", true);
        } catch (Exception e) {
            check("Merge with non-existent block is silently ignored", false);
        }

        // Merge with deleted block is silently ignored
        BlockDLL dll8 = new BlockDLL();
        dll8.insert(new BlockNode(1, 0, 1, makeContent(1, 0,  "hello"), "ROOT"));
        dll8.insert(new BlockNode(1, 1, 2, makeContent(1, 10, "world"), "1-0"));
        dll8.delete("1-1");
        dll8.mergeBlocks("1-0", "1-1");
        check("Merge with deleted block is silently ignored",
                dll8.getBlock("1-0").getContent().collectText().equals("hello"));
    }

    // ─── CharBlockMap ─────────────────────────────────────────────────────────

    private static void testCharBlockMap() {

        // charBlockMap correctly maps char to block on insert
        BlockDLL dll = new BlockDLL();
        dll.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        check("charBlockMap maps char to correct block on insert",
                "1-0".equals(dll.getBlockIDByCharID("1-2")));

        // charBlockMap updated after merge
        BlockDLL dll2 = new BlockDLL();
        dll2.insert(new BlockNode(1, 0, 1, makeContent(1, 0,  "hello"), "ROOT"));
        dll2.insert(new BlockNode(1, 1, 2, makeContent(1, 10, "world"), "1-0"));
        dll2.mergeBlocks("1-0", "1-1");
        check("charBlockMap updated after merge — chars from second block point to first",
                "1-0".equals(dll2.getBlockIDByCharID("1-10")));

        // charBlockMap updated after split
        BlockDLL dll3 = new BlockDLL();
        dll3.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "helloworld"), "ROOT"));
        BlockNode second = dll3.splitBlock(1, 10, 1, "1-0", "1-5");
        check("charBlockMap updated after split — moved chars point to new block",
                second != null && second.getBlockID().equals(dll3.getBlockIDByCharID("1-5")));

        // insertChar adds char to correct block via charBlockMap
        BlockDLL dll4 = new BlockDLL();
        dll4.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "helo"), "ROOT"));
        // insert 'l' after "hel" (parentID = "1-2")
        dll4.insertChar("1-2", new CharNode(1, 10, 1, 'l', "1-2"));
        check("insertChar adds character to correct block",
                dll4.getBlock("1-0").getContent().collectText().equals("hello"));

        // insertChar with unknown parentCharID is silently ignored
        BlockDLL dll5 = new BlockDLL();
        dll5.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        try {
            dll5.insertChar("9-9", new CharNode(1, 10, 1, 'x', "9-9"));
            check("insertChar with unknown parentCharID is silently ignored",
                    dll5.getBlock("1-0").getContent().collectText().equals("hello"));
        } catch (Exception e) {
            check("insertChar with unknown parentCharID is silently ignored", false);
        }

        // deleteChar removes character from correct block
        BlockDLL dll6 = new BlockDLL();
        dll6.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "helllo"), "ROOT"));
        dll6.deleteChar("1-2", 1, 20, 1);
        check("deleteChar removes character from correct block",
                dll6.getBlock("1-0").getContent().collectText().equals("hello"));

        // replaceChar replaces character correctly
        BlockDLL dll7 = new BlockDLL();
        dll7.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hxllo"), "ROOT"));
        dll7.replaceChar("1-1", new CharNode(1, 10, 1, 'e', "1-0"), 1, 20, 1);
        check("replaceChar replaces character correctly",
                dll7.getBlock("1-0").getContent().collectText().equals("hello"));

        // deleteChar on unknown charID is silently ignored
        BlockDLL dll8 = new BlockDLL();
        dll8.insert(new BlockNode(1, 0, 1, makeContent(1, 0, "hello"), "ROOT"));
        try {
            dll8.deleteChar("9-9", 1, 20, 1);
            check("deleteChar with unknown charID is silently ignored",
                    dll8.getBlock("1-0").getContent().collectText().equals("hello"));
        } catch (Exception e) {
            check("deleteChar with unknown charID is silently ignored", false);
        }
    }
}