package App;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocVersionRepository extends MongoRepository<DocVersion, String> {
    List<DocVersion> findByDocIdOrderByCreatedAtDesc(String docId);
    void deleteByDocId(String docId);
}