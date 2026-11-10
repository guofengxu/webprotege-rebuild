package wprebuild.app.access;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
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

    public static final String LAST_MANAGER = "LAST_MANAGER";

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);

    private final JdbcRoleAssignmentRepository assignments;

    private final JdbcUserRepository users;

    private final ApplicationEventPublisher events;

    public AccessService(
            JdbcRoleAssignmentRepository assignments, JdbcUserRepository users, ApplicationEventPublisher events) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.users = Objects.requireNonNull(users, "users");
        this.events = Objects.requireNonNull(events, "events");
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

    /**
     * Grants or replaces one member's role. The last {@link ProjectRole#CAN_MANAGE} cannot be
     * demoted. An unchanged role does not publish {@link RoleAssignmentsChanged}.
     */
    @Transactional
    public void assignRole(ProjectId projectId, UserId userId, ProjectRole role) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(role, "role");
        if (users.findById(userId).isEmpty()) {
            throw new NotFoundException("user not found");
        }
        assignments.lock(projectId);
        Optional<ProjectRole> current = assignments.find(projectId, userId);
        if (current.isPresent() && current.get() == role) {
            return;
        }
        if (current.orElse(null) == ProjectRole.CAN_MANAGE
                && role != ProjectRole.CAN_MANAGE
                && managers(projectId) < 2) {
            throw new ConflictException(LAST_MANAGER, "the last project manager cannot be removed");
        }
        assignments.assign(projectId, userId, role);
        publish(projectId);
    }

    /** Removes one member. The last {@link ProjectRole#CAN_MANAGE} cannot be removed. */
    @Transactional
    public void removeRole(ProjectId projectId, UserId userId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        assignments.lock(projectId);
        ProjectRole current = assignments
                .find(projectId, userId)
                .orElseThrow(() -> new NotFoundException("project member not found"));
        if (current == ProjectRole.CAN_MANAGE && managers(projectId) < 2) {
            throw new ConflictException(LAST_MANAGER, "the last project manager cannot be removed");
        }
        assignments.remove(projectId, userId);
        publish(projectId);
    }

    private int managers(ProjectId projectId) {
        int count = 0;
        for (RoleAssignment assignment : assignments.list(projectId)) {
            if (assignment.role() == ProjectRole.CAN_MANAGE) {
                count++;
            }
        }
        return count;
    }

    private void publish(ProjectId projectId) {
        RoleAssignmentsChanged event = new RoleAssignmentsChanged(projectId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publishEvent(event);
            }
        });
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
