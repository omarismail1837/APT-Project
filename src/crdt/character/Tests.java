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
        long t = System.currentTimeMillis();

        CharDLL crdt = new CharDLL();
        check("Empty document returns empty string", crdt.collectText().isEmpty());

        CharDLL crdt2 = new CharDLL();
        crdt2.insert(new CharNode(1, 0, t, 'h', "ROOT"));
        check("Single insert produces correct character", crdt2.collectText().equals("h"));

        CharDLL crdt22 = new CharDLL();
        crdt22.insert(new CharNode(1, 0, 1, 'l', "1-2"));
        check("Orphan insert is silently ignored", crdt22.collectText().isEmpty());

        CharDLL crdt3 = new CharDLL();
        crdt3.insert(new CharNode(1, 0, System.currentTimeMillis(), 'h', "ROOT"));
        crdt3.insert(new CharNode(1, 1, System.currentTimeMillis(), 'e', "1-0"));
        crdt3.insert(new CharNode(1, 2, System.currentTimeMillis(), 'l', "1-1"));
        crdt3.insert(new CharNode(1, 3, System.currentTimeMillis(), 'l', "1-2"));
        crdt3.insert(new CharNode(1, 4, System.currentTimeMillis(), 'o', "1-3"));
        crdt3.insert(new CharNode(1, 5, System.currentTimeMillis(), 'o', "1-4"));
        crdt3.insert(new CharNode(1, 6, System.currentTimeMillis(), ' ', "1-5"));
        crdt3.insert(new CharNode(1, 7, System.currentTimeMillis(), 'w', "1-6"));
        crdt3.insert(new CharNode(1, 8, System.currentTimeMillis(), 'o', "1-7"));
        crdt3.insert(new CharNode(1, 9, System.currentTimeMillis(), 'r', "1-8"));
        crdt3.insert(new CharNode(1, 10, System.currentTimeMillis(), 'l', "1-9"));
        crdt3.insert(new CharNode(1, 11, System.currentTimeMillis(), 'd', "1-10"));
        check("Sequential inserts produce correct text", crdt3.collectText().equals("helloo world"));

        crdt3.delete("1-4");
        check("Deleting a character removes it from collected text", crdt3.collectText().equals("hello world"));

        CharDLL crdt4 = new CharDLL();
        crdt4.insert(new CharNode(1, 0, 1, 'h', "ROOT"));
        crdt4.insert(new CharNode(1, 0, 1, 'h', "ROOT"));
        check("Duplicate insert is silently ignored", crdt4.collectText().equals("h"));

        CharDLL crdt5 = new CharDLL();
        crdt5.insert(new CharNode(1, 0, 1, 'h', "ROOT"));
        crdt5.delete("1-0");
        crdt5.delete("1-0");
        check("Double delete is silently ignored", crdt5.collectText().isEmpty());

        crdt5.insert(new CharNode(1, 1, 2, 'e', "1-0"));
        check("Child of deleted node is inserted correctly", crdt5.collectText().equals("e"));

        CharDLL crdt6 = new CharDLL();
        crdt6.insert(new CharNode(1, 0, 1, 'A', "ROOT"));
        crdt6.insert(new CharNode(1, 1, 2, 'B', "1-0"));
        crdt6.insert(new CharNode(1, 2, 3, 'C', "1-1"));
        crdt6.delete("1-0");
        check("Deleting a node preserves its children in collected text", crdt6.collectText().equals("BC"));

        CharDLL crdt7 = new CharDLL();
        try {
            crdt7.delete("fake-id");
            check("Delete of non-existent node is silently ignored", true);
        } catch (Exception e) {
            check("Delete of non-existent node is silently ignored", false);
        }

        CharDLL crdt8 = new CharDLL();
        crdt8.insert(new CharNode(1, 0, 1, 'h', "ROOT"));
        crdt8.insert(new CharNode(1, 1, 2, 'i', "1-0"));
        crdt8.delete("1-0");
        crdt8.delete("1-1");
        check("Deleting all characters returns empty string", crdt8.collectText().isEmpty());
    }

    private static void testOrdering()
    {
        CharDLL crdt = new CharDLL();
        crdt.insert(new CharNode(1, 0, System.currentTimeMillis(), 'A', "ROOT"));
        crdt.insert(new CharNode(2, 0, System.currentTimeMillis() + 1, 'P', "ROOT"));
        crdt.insert(new CharNode(3, 0, System.currentTimeMillis() + 2, 'T', "ROOT"));
        check("Higher timestamp placed before lower timestamp sibling", crdt.collectText().equals("TPA"));

        CharDLL crdt2 = new CharDLL();
        long t = System.currentTimeMillis() + 3;
        crdt2.insert(new CharNode(1, 0, t, 'A', "ROOT"));
        crdt2.insert(new CharNode(2, 0, t, 'P', "ROOT"));
        crdt2.insert(new CharNode(3, 0, t, 'T', "ROOT"));
        check("Lower siteID wins when timestamps are equal", crdt2.collectText().equals("APT"));

        CharDLL dll1 = new CharDLL();
        CharDLL dll2 = new CharDLL();
        dll1.insert(new CharNode(1, 0, 1, 'A', "ROOT"));
        dll1.insert(new CharNode(2, 0, 2, 'B', "ROOT"));
        dll1.insert(new CharNode(3, 0, 3, 'C', "ROOT"));
        dll2.insert(new CharNode(3, 0, 3, 'C', "ROOT"));
        dll2.insert(new CharNode(2, 0, 2, 'B', "ROOT"));
        dll2.insert(new CharNode(1, 0, 1, 'A', "ROOT"));
        check("Same operations in different order converge to same text", dll1.collectText().equals(dll2.collectText()));
    }

    private static void testDepthAndSiblings()
    {
        CharDLL dll1 = new CharDLL();
        var c1 = new CharNode(1, 0, 1, 'Y', "ROOT");
        var c2 = new CharNode(1, 1, 2, 'N', "1-0");
        dll1.insert(c1);
        dll1.insert(c2);
        check("Depth increments correctly per level", c1.getDepth() == 1 && c2.getDepth() == 2);

        CharDLL dll2 = new CharDLL();
        c1 = new CharNode(1, 0, 1, 'Y', "ROOT");
        dll2.insert(c1);
        dll2.insert(new CharNode(1, 1, 2, 'a', "1-0"));
        dll2.insert(new CharNode(1, 2, 3, 's', "1-1"));
        dll2.insert(new CharNode(1, 3, 4, 'm', "1-2"));
        dll2.insert(new CharNode(2, 0, 1, 'N', "ROOT"));
        check("Losing sibling is placed after winning sibling's descendants", dll2.collectText().equals("YasmN"));

        CharDLL dll3 = new CharDLL();
        c1 = new CharNode(1, 0, 1, 'Y', "ROOT");
        dll3.insert(c1);
        dll3.insert(new CharNode(1, 1, 2, 'a', "1-0"));
        dll3.insert(new CharNode(1, 2, 3, 's', "1-1"));
        dll3.insert(new CharNode(1, 3, 4, 'm', "1-2"));
        dll3.insert(new CharNode(2, 0, 5, 'N', "ROOT"));
        dll3.insert(new CharNode(2, 1, 6, 'a', "2-0"));
        dll3.insert(new CharNode(2, 2, 7, 'd', "2-1"));
        dll3.insert(new CharNode(2, 3, 8, 'a', "2-2"));
        dll3.insert(new CharNode(1, 4, 9, 'i', "1-3"));
        dll3.insert(new CharNode(1, 5, 10, 'n', "1-4"));
        dll3.insert(new CharNode(3, 0, 1, 'L', "ROOT"));
        check("Sibling that loses to all others is placed at end of siblings", dll3.collectText().equals("NadaYasminL"));
    }
}