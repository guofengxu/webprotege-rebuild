package wprebuild.app.access;

import java.util.Objects;
import wprebuild.kernel.id.UserId;

/** Public user identity for member pickers. Does not include email or roles. */
public record UserSummary(UserId userId, String displayName) {

    public UserSummary {
        Objects.requireNonNull(userId, "user id");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
    }
}
