package wprebuild.app.access;

import java.util.Objects;
import wprebuild.kernel.id.UserId;

/**
 * Published after a user is soft-deleted (P6-29). P6-52 listens so that signed-in sessions for this
 * account are revoked immediately.
 */
public record UserDeleted(UserId userId) {

    public UserDeleted {
        Objects.requireNonNull(userId, "user id");
    }
}
