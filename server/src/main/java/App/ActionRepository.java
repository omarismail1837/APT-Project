package App;

import App.crdt.action.Action;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ActionRepository extends MongoRepository<Action, String> {
    // Spring automatically writes the query to find actions by docId
    List<Action> findByDocId(String documentId);
}