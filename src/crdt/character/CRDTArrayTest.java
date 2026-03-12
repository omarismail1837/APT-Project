package crdt.character;

public class CRDTArrayTest {
    public static void main(String[] args) {
        test_basicInsert();
        test_delete();
        test_convergence();
        test_blogExample();
    }

    private static void test_basicInsert() {
        System.out.println("=== test_basicInsert ===");
        CRDTArray crdt = new CRDTArray();
        CharNode H = new CharNode(0, 0, 1, 'H', null);
        CharNode i = new CharNode(0, 1, 2, 'i', "0-0");
        CharNode excl = new CharNode(0, 2, 3, '!', "0-1");
        crdt.insert(H);
        crdt.insert(i);
        crdt.insert(excl);
        crdt.printTree();
        System.out.println("Text: " + crdt.collectText());
        System.out.println();
    }

    private static void test_delete() {
        System.out.println("=== test_delete ===");
        CRDTArray crdt = new CRDTArray();
        CharNode H = new CharNode(0, 0, 1, 'H', null);
        CharNode i = new CharNode(0, 1, 2, 'i', "0-0");
        CharNode excl = new CharNode(0, 2, 3, '!', "0-1");
        crdt.insert(H);
        crdt.insert(i);
        crdt.insert(excl);
        crdt.delete(i);
        crdt.printTree();
        System.out.println("Text: " + crdt.collectText());
        System.out.println();
    }

    private static void test_convergence()
    {
        System.out.println("=== test_convergence ===");
        // Peer 0 receives A then B, Peer 1 receives B then A
        CRDTArray peer0 = new CRDTArray();
        peer0.insert(new CharNode(0, 0, 1, 'A', null));
        peer0.insert(new CharNode(1, 0, 1, 'B', null));

        CRDTArray peer1 = new CRDTArray();
        peer1.insert(new CharNode(1, 0, 1, 'B', null));
        peer1.insert(new CharNode(0, 0, 1, 'A', null));

        System.out.println("Peer 0:");
        peer0.printTree();
        System.out.println("Peer 1:");
        peer1.printTree();
        System.out.println("Peer 0 text: " + peer0.collectText());
        System.out.println("Peer 1 text: " + peer1.collectText());
        System.out.println("Converged: " + peer0.collectText().equals(peer1.collectText()));
        System.out.println();
    }

    private static void test_blogExample() {
        System.out.println("=== test_blogExample ===");
        CRDTArray crdt = new CRDTArray();

        // Peer 1 inserts "Example " — chained, all root children starting from null
        crdt.insert(new CharNode(1, 0, 1, 'E', null));
        crdt.insert(new CharNode(1, 1, 2, 'x', "1-0"));
        crdt.insert(new CharNode(1, 2, 3, 'a', "1-1"));
        crdt.insert(new CharNode(1, 3, 4, 'm', "1-2"));
        crdt.insert(new CharNode(1, 4, 5, 'p', "1-3"));
        crdt.insert(new CharNode(1, 5, 6, 'l', "1-4"));
        crdt.insert(new CharNode(1, 6, 7, 'e', "1-5"));
        crdt.insert(new CharNode(1, 7, 8, ' ', "1-6"));

        // Peer 2 inserts "text" — chained, after ' ' (1-7)
        crdt.insert(new CharNode(2, 0, 1, 't', "1-7"));
        crdt.insert(new CharNode(2, 1, 2, 'e', "2-0"));
        crdt.insert(new CharNode(2, 2, 3, 'x', "2-1"));
        crdt.insert(new CharNode(2, 3, 4, 't', "2-2"));

        // Peer 3 inserts "!" — after 't' (2-3)
        crdt.insert(new CharNode(3, 0, 1, '!', "2-3"));
        crdt.insert(new CharNode(3, 1, 2, '"', "3-0"));

        // Peer 4 inserts '"' at the start — null parent, higher counter to go before Peer 1
        crdt.insert(new CharNode(4, 0, 2, '"', null));

        crdt.printTree();
        System.out.println("Text: " + crdt.collectText());
        System.out.println("Expected: \"Example text!\"");
        System.out.println("PASS: " + crdt.collectText().equals("\"Example text!\""));
        System.out.println();
    }
}