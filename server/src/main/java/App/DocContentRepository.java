package App;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocContentRepository extends MongoRepository<DocContent, String> {
     DocContent findByDocId(String docId);
}
