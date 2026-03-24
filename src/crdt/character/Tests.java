package crdt.character;

public class Tests {
    private static int passes = 0;
    private static int failures = 0;

    public static void main()
    {
        testBasicOperations();
        testOrdering();
        testDepthAndSiblings();
        System.out.println(passes + (passes == 1 ? " Pass & " : " Passes & ") + failures + (failures == 1 ? " Failure" : " Failures"));
    }

    private static void check(String testName, boolean cond)
    {
        if (cond) {
            System.out.println("PASS: " + testName);
            passes++;
        }
        else {
            System.out.println("FAIL: " + testName);
            failures++;
        }
    }

    private static void testBasicOperations()
    {
        // Empty CharDLL
        CharDLL crdt = new CharDLL(0,0,0);
        check("Empty document returns empty string", crdt.collectText().isEmpty());

        // One Character
        CharDLL crdt2 = new CharDLL(0,0,0);
        crdt2.insert(new CharNode(1, 0, 1, 'h', crdt2.getHeadID()));
        check("Single insert produces correct character", crdt2.collectText().equals("h"));

        // Orphan
        CharDLL crdt22 = new CharDLL(0,0,0);
        crdt22.insert(new CharNode(1, 0, 1, 'l', "1-2"));
        check("Orphan insert is silently ignored", crdt22.collectText().isEmpty());

        // Sequential inserts (no conflicts)
        CharDLL crdt3 = new CharDLL(0,0,0);
        crdt3.insert(new CharNode(1, 0, 1, 'h', crdt3.getHeadID()));
        crdt3.insert(new CharNode(1, 1, 2, 'e', "1-0"));
        crdt3.insert(new CharNode(1, 2, 3, 'l', "1-1"));
        crdt3.insert(new CharNode(1, 3, 4, 'l', "1-2"));
        crdt3.insert(new CharNode(1, 4, 5, 'o', "1-3"));
        crdt3.insert(new CharNode(1, 5, 6, 'o', "1-4"));
        crdt3.insert(new CharNode(1, 6, 7, ' ', "1-5"));
        crdt3.insert(new CharNode(1, 7, 8, 'w', "1-6"));
        crdt3.insert(new CharNode(1, 8, 9, 'o', "1-7"));
        crdt3.insert(new CharNode(1, 9, 10, 'r', "1-8"));
        crdt3.insert(new CharNode(1, 10, 11, 'l', "1-9"));
        crdt3.insert(new CharNode(1, 11, 12, 'd', "1-10"));
        check("Sequential inserts produce correct text", crdt3.collectText().equals("helloo world"));

        // Deletion
        crdt3.delete("1-4");
        check("Deleting a character removes it from collected text", crdt3.collectText().equals("hello world"));

        // Duplicate insert (same site & clock)
        // Same clock with same siteID should never occur
        CharDLL crdt4 = new CharDLL(0,0,0);
        crdt4.insert(new CharNode(1, 0, 1, 'h', crdt4.getHeadID()));
        crdt4.insert(new CharNode(1, 0, 1, 'h', crdt4.getHeadID()));
        check("Duplicate insert is silently ignored", crdt4.collectText().equals("h"));

        // Trying to delete the same node twice
        CharDLL crdt5 = new CharDLL(0,0,0);
        crdt5.insert(new CharNode(1, 0, 1, 'h', crdt5.getHeadID()));
        crdt5.delete("1-0");
        crdt5.delete("1-0");
        check("Double delete is silently ignored", crdt5.collectText().isEmpty());

        // Child of a tombstone
        crdt5.insert(new CharNode(1, 1, 2, 'e', "1-0"));
        check("Child of deleted node is inserted correctly", crdt5.collectText().equals("e"));

        // Deleting a node in the middle
        CharDLL crdt6 = new CharDLL(0,0,0);
        crdt6.insert(new CharNode(1, 0, 1, 'A', crdt6.getHeadID()));
        crdt6.insert(new CharNode(1, 1, 2, 'B', "1-0"));
        crdt6.insert(new CharNode(1, 2, 3, 'C', "1-1"));
        crdt6.delete("1-0");
        check("Deleting a node preserves its children in collected text", crdt6.collectText().equals("BC"));

        // Deleting a non-existent node doesn't throw an error
        CharDLL crdt7 = new CharDLL(0,0,0);
        try {
            crdt7.delete("fake-id");
            check("Delete of non-existent node is silently ignored", true);
        } catch (Exception e) {
            check("Delete of non-existent node is silently ignored", false);
        }

        // Deleting more than one character
        CharDLL crdt8 = new CharDLL(0,0,0);
        crdt8.insert(new CharNode(1, 0, 1, 'h', crdt8.getHeadID()));
        crdt8.insert(new CharNode(1, 1, 2, 'i', "1-0"));
        crdt8.delete("1-0");
        crdt8.delete("1-1");
        check("Deleting all characters returns empty string", crdt8.collectText().isEmpty());
    }

    private static void testOrdering()
    {
        // Ordering via time
        CharDLL crdt = new CharDLL(0,0,0);
        crdt.insert(new CharNode(1, 0, 1, 'A', crdt.getHeadID()));
        crdt.insert(new CharNode(2, 0, 2, 'P', crdt.getHeadID()));
        crdt.insert(new CharNode(3, 0, 3, 'T', crdt.getHeadID()));
        check("Higher timestamp placed before lower timestamp sibling", crdt.collectText().equals("TPA"));

        // Same time -> use siteID as tiebreaker
        CharDLL crdt2 = new CharDLL(0,0,0);
        crdt2.insert(new CharNode(1, 0, 1, 'A', crdt2.getHeadID()));
        crdt2.insert(new CharNode(2, 0, 2, 'P', crdt2.getHeadID()));
        crdt2.insert(new CharNode(3, 0, 3, 'T', crdt2.getHeadID()));
        check("Lower siteID wins when timestamps are equal", crdt2.collectText().equals("APT"));

        // Ops with same params converge (even if insertion order is different in code)
        CharDLL dll1 = new CharDLL(0,0,0);
        CharDLL dll2 = new CharDLL(0,0,0);
        dll1.insert(new CharNode(1, 0, 1, 'A', dll1.getHeadID()));
        dll1.insert(new CharNode(2, 0, 2, 'B', dll1.getHeadID()));
        dll1.insert(new CharNode(3, 0, 3, 'C', dll1.getHeadID()));
        dll2.insert(new CharNode(3, 0, 3, 'C', dll2.getHeadID()));
        dll2.insert(new CharNode(2, 0, 2, 'B', dll2.getHeadID()));
        dll2.insert(new CharNode(1, 0, 1, 'A', dll2.getHeadID()));
        check("Same operations in different order converge to same text", dll1.collectText().equals(dll2.collectText()));
    }

    private static void testDepthAndSiblings()
    {
        // Depth incrementing
        CharDLL dll1 = new CharDLL(0,0,0);
        var c1 = new CharNode(1, 0, 1, 'Y', dll1.getHeadID());
        var c2 = new CharNode(1, 1, 2, 'N', "1-0");
        dll1.insert(c1);
        dll1.insert(c2);
        check("Depth increments correctly per level", c1.getDepth() == 1 && c2.getDepth() == 2);

        // Skip descendants
        CharDLL dll2 = new CharDLL(0,0,0);
        dll2.insert(new CharNode(1, 0, 1, 'Y', dll2.getHeadID()));
        dll2.insert(new CharNode(1, 1, 2, 'a', "1-0"));
        dll2.insert(new CharNode(1, 2, 3, 's', "1-1"));
        dll2.insert(new CharNode(1, 3, 4, 'm', "1-2"));
        dll2.insert(new CharNode(2, 0, 1, 'N', dll2.getHeadID()));
        check("Losing sibling is placed after winning sibling's descendants", dll2.collectText().equals("YasmN"));

        // Skip descendants but with 2 siblings
        CharDLL dll3 = new CharDLL(0,0,0);
        dll3.insert(new CharNode(1, 0, 1, 'Y', dll3.getHeadID()));
        dll3.insert(new CharNode(1, 1, 2, 'a', "1-0"));
        dll3.insert(new CharNode(1, 2, 3, 's', "1-1"));
        dll3.insert(new CharNode(1, 3, 4, 'm', "1-2"));
        dll3.insert(new CharNode(2, 0, 5, 'N', dll3.getHeadID()));
        dll3.insert(new CharNode(2, 1, 6, 'a', "2-0"));
        dll3.insert(new CharNode(2, 2, 7, 'd', "2-1"));
        dll3.insert(new CharNode(2, 3, 8, 'a', "2-2"));
        dll3.insert(new CharNode(1, 4, 9, 'i', "1-3"));
        dll3.insert(new CharNode(1, 5, 10, 'n', "1-4"));
        dll3.insert(new CharNode(3, 0, 1, 'L', dll3.getHeadID()));
        check("Sibling that loses to all others is placed at end of siblings", dll3.collectText().equals("NadaYasminL"));
    }
}