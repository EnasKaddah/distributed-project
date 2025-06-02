import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

public class User implements Serializable {
    private String username;
    private String password;
    private String department;
    private Set<String> permissions;
    private String token;

    public User(String username, String password, String department) {
        this.username = username;
        this.password = password;
        this.department = department;
        this.permissions = new HashSet<>();
    }

    // Getters and setters
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getDepartment() { return department; }
    public Set<String> getPermissions() { return permissions; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public void addPermission(String permission) {
        permissions.add(permission);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}