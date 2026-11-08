package wprebuild.app.access;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.shared.PermissionDeniedException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Answers whether a user may perform an action (BS-10). Missing roles deny the action.
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Service
public class AccessService {

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);

    private final JdbcRoleAssignmentRepository assignments;

    private final JdbcUserRepository users;

    public AccessService(JdbcRoleAssignmentRepository assignments, JdbcUserRepository users) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.users = Objects.requireNonNull(users, "users");
    }

    public boolean can(UserId userId, Action action) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(action, "action");
        if (action.projectScoped()) {
            return false;
        }
        List<String> roles = appRoles(userId);
        if (roles.contains(AppRole.SYSTEM_ADMIN.name())) {
            return AppRole.SYSTEM_ADMIN.allows(action);
        }
        return roles.contains(AppRole.PROJECT_CREATOR.name()) && AppRole.PROJECT_CREATOR.allows(action);
    }

    public boolean can(UserId userId, ProjectId projectId, Action action) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(action, "action");
        if (!action.projectScoped()) {
            return false;
        }
        if (appRoles(userId).contains(AppRole.SYSTEM_ADMIN.name())) {
            return ProjectRole.CAN_MANAGE.allows(action);
        }
        return assignments
                .find(projectId, userId)
                .map(role -> role.allows(action))
                .orElse(false);
    }

    public void require(UserId userId, Action action) {
        if (!can(userId, action)) {
            throw denied(userId, action);
        }
    }

    public void require(UserId userId, ProjectId projectId, Action action) {
        if (!can(userId, projectId, action)) {
            throw denied(userId, projectId, action);
        }
    }

    /** The user who creates a project receives {@link ProjectRole#CAN_MANAGE} on it. */
    @Transactional
    public void grantCreator(ProjectId projectId, UserId userId) {
        assignments.assign(projectId, userId, ProjectRole.CAN_MANAGE);
    }

    private List<String> appRoles(UserId userId) {
        return users.appRoles(userId);
    }

    private static PermissionDeniedException denied(UserId userId, Action action) {
        log.info("permission denied for {} action {}", safe(userId), action);
        return new PermissionDeniedException("permission denied");
    }

    private static PermissionDeniedException denied(UserId userId, ProjectId projectId, Action action) {
        log.info("permission denied for {} action {} on project {}", safe(userId), action, projectId);
        return new PermissionDeniedException("permission denied");
    }

    private static String safe(UserId userId) {
        return userId.value().replace('\r', ' ').replace('\n', ' ');
    }
}
