package wprebuild.app.project;

import java.util.Objects;
import org.springframework.stereotype.Component;
import wprebuild.app.access.AppRole;
import wprebuild.app.access.UserService;
import wprebuild.kernel.id.UserId;

/** BS-10: a system administrator sees every project, not only projects that list them as a member. */
@Component
class ProjectVisibility {

    private final UserService users;

    ProjectVisibility(UserService users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    boolean seesEveryProject(UserId userId) {
        return users.findById(Objects.requireNonNull(userId, "user id"))
                .map(account -> account.appRoles().contains(AppRole.SYSTEM_ADMIN.name()))
                .orElse(false);
    }
}
