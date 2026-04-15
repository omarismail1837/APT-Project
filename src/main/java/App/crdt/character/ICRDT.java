package App.crdt.character;

public interface ICRDT <T>{
    void insert(T node);
    void delete(String id);
    String collectText();
}
