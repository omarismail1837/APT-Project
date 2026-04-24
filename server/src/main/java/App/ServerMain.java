package App; // Must match your folder name

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class ServerMain {

    public static void main(String[] args) {
        // Railway injects this variable automatically
        String connectionString = System.getenv("MONGO_URL");

        if (connectionString == null) {
            System.err.println("Environment variable MONGO_URL not found!");
            return;
        }

        try (MongoClient mongoClient = MongoClients.create(connectionString)) {
            MongoDatabase database = mongoClient.getDatabase("test"); // Replace with your DB name

            // Quick ping to verify connection
            Document ping = new Document("ping", 1);
            database.runCommand(ping);

            System.out.println("Successfully connected to Railway MongoDB!");

        } catch (MongoException e) {
            System.err.println("Failed to connect: " + e.getMessage());
        }
    }

}