package App.crdt.user;

public class User {
    // Fields
    private String userId;
    private String clock;
    private String color;
    private String name;

    // Empty constructor
    public User() {
    }

    // Parameterized constructor
    public User(String userId, String clock, String color, String name) {
        this.userId = userId;
        this.clock = clock;
        this.color = color;
        this.name = name;
    }

    // Getters
    public String getUserId() {
        return userId;
    }

    public String getClock() {
        return clock;
    }

    public String getColor() {
        return color;
    }

    public String getName() {
        return name;
    }

    // Setters
    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setClock(String clock) {
        this.clock = clock;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public void setName(String name) {
        this.name = name;
    }
}
