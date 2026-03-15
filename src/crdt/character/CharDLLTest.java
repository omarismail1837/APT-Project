package crdt.character;

public class CharDLLTest {

    public static void main(String[] args) {
        test_sequentialInsert();
        test_concurrentInsertConvergence();
        test_insertAfterTombstone();
        test_threeSiteConvergence();
    }

    static void test_sequentialInsert() {
        CharDLL doc = new CharDLL();

        CharNode a = new CharNode(1, 1, 'A', null);
        CharNode b = new CharNode(1, 2, 'B', a.getCharID());
        CharNode c = new CharNode(1, 3, 'C', b.getCharID());

        doc.insert(a);
        doc.insert(b);
        doc.insert(c);

        System.out.println("test_sequentialInsert: " + doc.collectText()); // expected: ABC
    }

    static void test_concurrentInsertConvergence() {
        CharNode a = new CharNode(1, 1, 'A', null);
        CharNode b = new CharNode(1, 2, 'B', a.getCharID()); // clock 2
        CharNode x = new CharNode(2, 3, 'X', a.getCharID()); // clock 3, wins

        CharDLL site1 = new CharDLL();
        site1.insert(a);
        site1.insert(b);
        site1.insert(x);

        CharDLL site2 = new CharDLL();
        site2.insert(a);
        site2.insert(x);
        site2.insert(b);

        System.out.println("test_concurrentInsertConvergence site1: " + site1.collectText()); // expected: AXB
        System.out.println("test_concurrentInsertConvergence site2: " + site2.collectText()); // expected: AXB
    }

    static void test_insertAfterTombstone() {
        CharNode a = new CharNode(1, 1, 'A', null);
        CharNode b = new CharNode(1, 2, 'B', a.getCharID());
        CharNode c = new CharNode(2, 3, 'C', b.getCharID());

        CharDLL doc = new CharDLL();
        doc.insert(a);
        doc.insert(b);
        doc.delete(b.getCharID());
        doc.insert(c);

        System.out.println("test_insertAfterTombstone: " + doc.collectText()); // expected: AC
    }

    static void test_threeSiteConvergence() {
        CharNode a = new CharNode(1, 1, 'A', null); // clock 1
        CharNode b = new CharNode(2, 2, 'B', null); // clock 2
        CharNode c = new CharNode(3, 3, 'C', null); // clock 3, wins

        CharDLL site1 = new CharDLL();
        site1.insert(a);
        site1.insert(b);
        site1.insert(c);

        CharDLL site2 = new CharDLL();
        site2.insert(b);
        site2.insert(c);
        site2.insert(a);

        CharDLL site3 = new CharDLL();
        site3.insert(c);
        site3.insert(a);
        site3.insert(b);

        System.out.println("test_threeSiteConvergence site1: " + site1.collectText()); // expected: CBA
        System.out.println("test_threeSiteConvergence site2: " + site2.collectText()); // expected: CBA
        System.out.println("test_threeSiteConvergence site3: " + site3.collectText()); // expected: CBA
    }
}