package App;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocRepository extends MongoRepository<DocMetadata, String> {

    // Used to find a document when a user joins with an Edit Code
    Optional<DocMetadata> findByEditCode(String editCode);

    // Used to find a document when a user joins with a View Code
    Optional<DocMetadata> findByViewCode(String viewCode);

    // Helpful if you want to list all documents belonging to a specific user
    java.util.List<DocMetadata> findByOwnerId(String ownerId);

    @Query("{ 'docId' : ?0 }")
    @Update("{ '$set' : { 'lastModified' : ?1 } }")
    void updateLastModified(String docId, java.util.Date lastModified);
}