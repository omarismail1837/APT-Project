package crdt.character;

public interface ICRDT {
    void insert(CharNode c);
    void delete(String id);
    String collectText();
}
