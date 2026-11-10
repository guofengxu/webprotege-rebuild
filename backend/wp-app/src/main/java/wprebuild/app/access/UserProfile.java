package wprebuild.app.access;

import java.util.List;
import java.util.Objects;
import wprebuild.kernel.id.UserId;

/** A user as shown to an administrator. The password hash is not part of this view. */
public record UserProfile(
        UserId userId, String displayName, String email, boolean passwordResetRequired, List<String> appRoles) {

    public UserProfile {
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
        appRoles = List.copyOf(Objects.requireNonNull(appRoles, "app roles"));
    }

    public static UserProfile from(UserAccount account) {
        return new UserProfile(
                account.userId(),
                account.displayName(),
                account.email(),
                account.passwordResetRequired(),
                account.appRoles());
    }
}
