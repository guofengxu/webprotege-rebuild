package wprebuild.app.web.admin;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.UserId;

/** Replaces any user's password, including an account that has none. Requires {@code RESET_ANY_USER_PASSWORD}. */
public record ResetUserPassword(UserId userId, String newPassword) implements Command<Void> {

    public ResetUserPassword {
        Objects.requireNonNull(userId, "user id");
        newPassword = AccountText.password(newPassword);
    }

    @Override
    public String toString() {
        return "ResetUserPassword[userId=" + userId.value() + ", newPassword=***]";
    }
}
