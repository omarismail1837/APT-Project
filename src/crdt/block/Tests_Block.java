package crdt.block;

import crdt.character.CharDLL;
import crdt.character.CharNode;

public class Tests_Block {
    private static int passes = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        testBlockCreation();
        testBlockCopyIndependence();
        testLineCountLogic();
        testPasteOperations();

        System.out.println("\n" + passes + (passes == 1 ? " Pass & " : " Passes & ")
                + failures + (failures == 1 ? " Failure" : " Failures"));
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

    private static void testBlockCreation() {
        // Assuming BlockManager constructor creates a default empty block
        BlockDLL bm = new BlockDLL(1, 0, 0);
        check("New manager starts with one block (Sentinel/Root)", bm.getBlockCount() == 1);
    }

    private static void testBlockCopyIndependence() {
        long t = System.currentTimeMillis();
        CharDLL original = new CharDLL(1, 0, t);
        original.insert(new CharNode(1, 1, t, 'A', "ROOT_0"));

        // Test the copy function we wrote
        CharDLL cloned = original.copy(2, 100, t + 1);

        check("Clone has same content text", cloned.collectText().equals(original.collectText()));

        // Modify clone, original should stay same
        cloned.insert(new CharNode(2, 101, t + 2, 'B', "2-100"));

        check("Modifying clone does not affect original", !cloned.collectText().equals(original.collectText()));
        check("Clone characters have new SiteID", cloned.getHead().getNext().getSiteID() == 2);
    }

    private static void testLineCountLogic() {
        BlockNode block = new BlockNode(1, 0, 0, new CharDLL(1, 0, 0), "DOC_ROOT");
        CharDLL dll = block.getContentDLL();

        // Initial state (root usually has a \n or is considered 1 line)
        int initialLines = block.getLineCount();

        dll.insert(new CharNode(1, 1, 1, 'H', "ROOT_0"));
        dll.insert(new CharNode(1, 2, 2, '\n', "1-1")); // This should trigger lineCount++

        // You mentioned: if (c.getContent() == '\n') lineCount++;
        check("Inserting newline increases line count", block.getLineCount() > initialLines);

        block.deleteChar("1-2"); // Your delete function logic with lineCount--
        check("Deleting newline decreases line count", block.getLineCount() == initialLines);
    }

    private static void testPasteOperations() {
        BlockManager bm = new BlockManager(1, 0, 0);
        CharDLL clipBoard = new CharDLL(1, 0, 0);
        clipBoard.insert(new CharNode(1, 1, 1, 'P', "ROOT_0"));
        clipBoard.insert(new CharNode(1, 2, 2, 'V', "1-1"));

        BlockNode target = bm.getHeadBlock();

        // Testing your pasteBlock logic
        bm.pasteBlock(clipBoard, target, 1, 500, System.currentTimeMillis());

        check("Pasted block exists in manager", bm.getBlockCount() > 1);

        // Verify unique IDs in pasted content (should start at clock 501 as we discussed)
        BlockNode pastedBlock = bm.getHeadBlock().getNext();
        String firstCharID = pastedBlock.getContentDLL().getHead().getNext().getCharID();
        check("Pasted characters use unique clock offset", firstCharID.contains("501"));
    }
}
