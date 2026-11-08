package wprebuild.app.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * First administrator, created only when every field is set. Keys: {@code wp.bootstrap.admin.username},
 * {@code password}, and {@code email}.
 */
@ConfigurationProperties(prefix = "wp.bootstrap.admin")
public record BootstrapAdminProperties(String username, String password, String email) {

    public boolean configured() {
        return present(username) && present(password) && present(email);
    }

    public boolean partial() {
        boolean any = present(username) || present(password) || present(email);
        return any && !configured();
    }

    @Override
    public String toString() {
        return "BootstrapAdminProperties[username=" + username + ", password=" + (password == null ? "null" : "***")
                + ", email=" + email + "]";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
