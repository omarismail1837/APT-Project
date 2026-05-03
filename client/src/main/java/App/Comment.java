package App;

public class Comment {

    String text;
    String username;


    public String getUsername() {
        return username;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setUsername(String username) {
        this.username = username;
    }


    Comment(String username, String text) {
        this.username = username;
        this.text = text;
    }
}
