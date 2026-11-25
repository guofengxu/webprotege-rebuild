package wprebuild.app.access;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Creates accounts and checks passwords. The delegating encoder's default id is bcrypt.
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Service
public class UserService {

    /** Removing the only {@link AppRole#SYSTEM_ADMIN} is rejected. */
    public static final String LAST_SYSTEM_ADMIN = "LAST_SYSTEM_ADMIN";

    /** Soft-deleting oneself is rejected. */
    public static final String CANNOT_DELETE_SELF = "CANNOT_DELETE_SELF";

    /** Soft-deleting an already-deleted account's credentials is rejected. */
    public static final String USER_DELETED = "USER_DELETED";

    /** BCrypt ignores bytes past this point, so a longer password would be stored as a shorter one. */
    static final int BCRYPT_MAX_BYTES = 72;

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final JdbcUserRepository users;

    private final PasswordEncoder passwordEncoder;

    private final ApiKeyService apiKeys;

    private final AccessTokenService tokens;

    private final JdbcRoleAssignmentRepository assignments;

    private final ApplicationEventPublisher events;

    private final String dummyHash;

    public UserService(
            JdbcUserRepository users,
            PasswordEncoder passwordEncoder,
            @Lazy ApiKeyService apiKeys,
            @Lazy AccessTokenService tokens,
            JdbcRoleAssignmentRepository assignments,
            ApplicationEventPublisher events) {
        this.users = Objects.requireNonNull(users, "users");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.apiKeys = Objects.requireNonNull(apiKeys, "api keys");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.events = Objects.requireNonNull(events, "events");
        // Missing accounts pay the same BCrypt cost, so timing does not reveal whether the username exists.
        this.dummyHash = passwordEncoder.encode("timing-equalization");
    }

    @Transactional
    public UserAccount create(
            String userId, String displayName, String email, String rawPassword, List<String> appRoles) {
        UserId id = new UserId(userId);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        UserAccount account = new UserAccount(id, displayName, email, encode(rawPassword), appRoles, null);
        if (users.insert(account) == 0) {
            throw new ConflictException("user already exists: " + id.value());
        }
        return account;
    }

    /**
     * Soft-deletes an account. The {@code users} row stays so foreign keys ({@code projects.owner},
     * revision history) keep the original account id for audit. Personal data is cleared; API keys and
     * mobile tokens are revoked; project memberships are removed.
     */
    @Transactional
    public void delete(UserId userId, UserId actingAdmin) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(actingAdmin, "acting admin");
        if (actingAdmin.equals(userId)) {
            throw new ConflictException(CANNOT_DELETE_SELF, "an administrator cannot delete their own account");
        }
        users.lockUsers();
        UserAccount current = users.findById(userId).orElseThrow(() -> new NotFoundException("user not found"));
        if (current.deleted()) {
            return;
        }
        boolean removingAdmin = current.appRoles().contains(AppRole.SYSTEM_ADMIN.name());
        if (removingAdmin && users.countWithRole(AppRole.SYSTEM_ADMIN.name()) < 2) {
            throw new ConflictException(LAST_SYSTEM_ADMIN, "the last system administrator cannot be removed");
        }
        // Same per-project locks as AccessService.removeRole so a concurrent demotion cannot leave
        // a project without CAN_MANAGE.
        for (ProjectId projectId : assignments.managedProjects(userId)) {
            assignments.lock(projectId);
        }
        List<ProjectId> soleManagerProjects = assignments.soleManagerProjects(userId);
        if (!soleManagerProjects.isEmpty()) {
            List<String> projectIds =
                    soleManagerProjects.stream().map(ProjectId::toString).toList();
            throw new ConflictException(
                    AccessService.LAST_MANAGER,
                    "the last project manager cannot be removed",
                    Map.of("projectIds", projectIds));
        }
        Instant deletedAt = Instant.now();
        if (users.softDelete(userId, deletedAt) == 0) {
            throw new IllegalStateException("user disappeared while locked: " + userId.value());
        }
        apiKeys.revokeAll(userId);
        tokens.revokeAll(userId);
        List<ProjectId> memberships = assignments.projectsForUser(userId);
        assignments.removeAllForUser(userId);
        log.info("soft-deleted user {} by {}", userId.value(), actingAdmin.value());
        publishAfterCommit(userId, memberships);
    }

    @Transactional(readOnly = true)
    public UserPage list(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1 || size > UserPage.MAX_SIZE) {
            throw new IllegalArgumentException("size must be from 1 to " + UserPage.MAX_SIZE);
        }
        JdbcUserRepository.UserSlice slice = users.slice(size, (long) page * size);
        return new UserPage(slice.users(), page, size, slice.total());
    }

    /**
     * Replaces application roles. The last {@link AppRole#SYSTEM_ADMIN} cannot be removed. The table
     * lock makes that count stable with the update.
     */
    @Transactional
    public UserProfile setAppRoles(UserId userId, Set<AppRole> appRoles) {
        Objects.requireNonNull(userId, "user id");
        List<String> stored = roleNames(appRoles);
        users.lockUsers();
        UserAccount current = users.findById(userId).orElseThrow(() -> new NotFoundException("user not found"));
        if (current.deleted()) {
            throw new ConflictException(USER_DELETED, "user has been deleted");
        }
        boolean removingAdmin = current.appRoles().contains(AppRole.SYSTEM_ADMIN.name())
                && !stored.contains(AppRole.SYSTEM_ADMIN.name());
        if (removingAdmin && users.countWithRole(AppRole.SYSTEM_ADMIN.name()) < 2) {
            throw new ConflictException(LAST_SYSTEM_ADMIN, "the last system administrator cannot be removed");
        }
        if (users.updateAppRoles(userId, stored) == 0) {
            throw new IllegalStateException("user disappeared while roles were locked: " + userId.value());
        }
        return new UserProfile(
                current.userId(),
                current.displayName(),
                current.email(),
                current.passwordResetRequired(),
                stored,
                current.deletedAt());
    }

    public Optional<UserAccount> findById(UserId userId) {
        return users.findById(Objects.requireNonNull(userId, "user id"));
    }

    /** Candidate users for the member picker. Does not log the query text. */
    @Transactional(readOnly = true)
    public List<UserSummary> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return users.search(query, limit);
    }

    @Transactional
    public void setPassword(UserId userId, String rawPassword) {
        Objects.requireNonNull(userId, "user id");
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        UserAccount current = users.findById(userId).orElseThrow(() -> new NotFoundException("user not found"));
        if (current.deleted()) {
            throw new ConflictException(USER_DELETED, "user has been deleted");
        }
        String hash = encode(rawPassword);
        if (users.updatePasswordHash(userId, hash) == 0) {
            throw new NotFoundException("user not found");
        }
    }

    private void publishAfterCommit(UserId userId, List<ProjectId> memberships) {
        Runnable publish = () -> {
            for (ProjectId projectId : memberships) {
                events.publishEvent(new RoleAssignmentsChanged(projectId));
            }
            events.publishEvent(new UserDeleted(userId));
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }

    private static List<String> roleNames(Set<AppRole> appRoles) {
        if (appRoles == null) {
            throw new IllegalArgumentException("app roles must not be null");
        }
        EnumSet<AppRole> ordered = EnumSet.noneOf(AppRole.class);
        for (AppRole role : appRoles) {
            if (role == null) {
                throw new IllegalArgumentException("app role must not be null");
            }
            ordered.add(role);
        }
        return ordered.stream().map(AppRole::name).toList();
    }

    public PasswordVerification verifyPassword(String username, String rawPassword) {
        if (username == null || username.isBlank()) {
            return new PasswordVerification.Rejected();
        }
        UserAccount account;
        try {
            account = users.findById(new UserId(username)).orElse(null);
        } catch (IllegalArgumentException ex) {
            return new PasswordVerification.Rejected();
        }
        if (account == null) {
            passwordEncoder.matches(timingPassword(rawPassword), dummyHash);
            return new PasswordVerification.Rejected();
        }
        if (account.deleted()) {
            passwordEncoder.matches(timingPassword(rawPassword), dummyHash);
            return new PasswordVerification.Rejected();
        }
        if (account.passwordResetRequired()) {
            return new PasswordVerification.ResetRequired();
        }
        if (!acceptable(rawPassword)) {
            // Same BCrypt cost as a missing account, so a blank or over-long password is not an oracle.
            passwordEncoder.matches(timingPassword(rawPassword), dummyHash);
            return new PasswordVerification.Rejected();
        }
        if (!passwordEncoder.matches(rawPassword, account.passwordHash())) {
            return new PasswordVerification.Rejected();
        }
        return new PasswordVerification.Accepted(account);
    }

    private String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return null;
        }
        if (exceedsBcrypt(rawPassword)) {
            throw new IllegalArgumentException("password must be at most 72 UTF-8 bytes");
        }
        return passwordEncoder.encode(rawPassword);
    }

    private String timingPassword(String rawPassword) {
        return acceptable(rawPassword) ? rawPassword : "timing-placeholder";
    }

    private static boolean acceptable(String rawPassword) {
        return rawPassword != null && !rawPassword.isBlank() && !exceedsBcrypt(rawPassword);
    }

    private static boolean exceedsBcrypt(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}
