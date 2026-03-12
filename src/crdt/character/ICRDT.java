package crdt.character;

public interface ICRDT {
    void insert(CharNode c);
    void delete(CharNode c);
    String collectText();
    CharNode getNode(String id);
}
