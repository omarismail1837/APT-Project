package App;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "users")
public class UserAccount {
    @Id
    private String userId;

    private String username;
    private String password;
    // no email because not needed

    public UserAccount() {}
    public UserAccount(String username, String password)
    {
        this.username = username;
        this.password = password; // maybe use hash later
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUserId() {
        return userId;
    }
}
