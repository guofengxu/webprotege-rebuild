package wprebuild.app.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import wprebuild.kernel.id.UserId;

/** A row of {@code users}. {@code passwordHash} is absent when the account must reset its password. */
public record UserAccount(UserId userId, String displayName, String email, String passwordHash, List<String> appRoles) {

    public UserAccount {
        Objects.requireNonNull(userId, "user id");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        displayName = displayName.strip();
        if (email != null) {
            email = email.strip();
            if (email.isEmpty()) {
                email = null;
            }
        }
        if (passwordHash != null && passwordHash.isBlank()) {
            passwordHash = null;
        }
        appRoles = normalize(appRoles);
    }

    /** {@code password_hash} null or blank: migrated accounts cannot sign in until a password is set. */
    public boolean passwordResetRequired() {
        return passwordHash == null || passwordHash.isBlank();
    }

    @Override
    public String toString() {
        return "UserAccount[userId=" + userId.value() + ", displayName=" + displayName + ", passwordResetRequired="
                + passwordResetRequired() + ", appRoles=" + appRoles + "]";
    }

    private static List<String> normalize(List<String> appRoles) {
        if (appRoles == null) {
            throw new IllegalArgumentException("app roles must not be null");
        }
        List<String> normalized = new ArrayList<>();
        for (String role : appRoles) {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("app role must not be blank");
            }
            normalized.add(role.strip());
        }
        return List.copyOf(normalized);
    }
}
