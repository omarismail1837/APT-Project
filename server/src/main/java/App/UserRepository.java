package App;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface UserRepository extends MongoRepository<UserAccount, String> {
    Optional<UserAccount> findByUsername(String username); // login finds accoutn and checks password
    boolean existsByUsername(String username); // signup checks if username exists
}