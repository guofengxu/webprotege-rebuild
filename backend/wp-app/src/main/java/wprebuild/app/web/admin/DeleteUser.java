package wprebuild.app.web.admin;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.UserId;

/** Soft-deletes one account. Requires {@code DELETE_ANY_ACCOUNT}. */
public record DeleteUser(UserId userId) implements Command<Void> {

    public DeleteUser {
        Objects.requireNonNull(userId, "user id");
    }
}
