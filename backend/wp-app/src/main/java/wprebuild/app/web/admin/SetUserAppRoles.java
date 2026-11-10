package wprebuild.app.web.admin;

import java.util.Objects;
import java.util.Set;
import wprebuild.app.access.AppRole;
import wprebuild.app.access.UserProfile;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.UserId;

/** Replaces one user's application roles. Requires {@code EDIT_APPLICATION_SETTINGS}. */
public record SetUserAppRoles(UserId userId, Set<AppRole> appRoles) implements Command<UserProfile> {

    public SetUserAppRoles {
        Objects.requireNonNull(userId, "user id");
        if (appRoles == null) {
            throw new IllegalArgumentException("app roles must not be null");
        }
        for (AppRole role : appRoles) {
            if (role == null) {
                throw new IllegalArgumentException("app role must not be null");
            }
        }
        appRoles = Set.copyOf(appRoles);
    }
}
