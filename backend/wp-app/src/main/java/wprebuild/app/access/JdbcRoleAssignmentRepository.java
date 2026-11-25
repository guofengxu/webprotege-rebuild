package wprebuild.app.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** {@code role_assignments}. A user has at most one project role on a project (BS-10). */
@Repository
public class JdbcRoleAssignmentRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcRoleAssignmentRepository.class);

    private final JdbcClient jdbc;

    public JdbcRoleAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<ProjectRole> find(ProjectId projectId, UserId userId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        return jdbc.sql("""
                        select role
                        from role_assignments
                        where project_id = ? and user_id = ?
                        """)
                .param(projectId.value())
                .param(userId.value())
                .query((rs, row) -> rs.getString("role"))
                .optional()
                .flatMap(JdbcRoleAssignmentRepository::parse);
    }

    public void assign(ProjectId projectId, UserId userId, ProjectRole role) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(role, "role");
        int stored = jdbc.sql("""
                        insert into role_assignments (project_id, user_id, role)
                        values (?, ?, ?)
                        on conflict (project_id, user_id) do update set role = excluded.role
                        """)
                .param(projectId.value())
                .param(userId.value())
                .param(role.name())
                .update();
        if (stored != 1) {
            throw new IllegalStateException("role assignment was not stored");
        }
    }

    /** Locks the project's role rows so the last-manager check and the write stay together. */
    public void lock(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        jdbc.sql("select user_id from role_assignments where project_id = ? for update")
                .param(projectId.value())
                .query((rs, row) -> rs.getString("user_id"))
                .list();
    }

    public void remove(ProjectId projectId, UserId userId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        jdbc.sql("delete from role_assignments where project_id = ? and user_id = ?")
                .param(projectId.value())
                .param(userId.value())
                .update();
    }

    public List<RoleAssignment> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return jdbc.sql("""
                        select user_id, role
                        from role_assignments
                        where project_id = ?
                        order by user_id
                        """).param(projectId.value()).query((rs, row) -> assignment(projectId, rs)).list().stream()
                .flatMap(Optional::stream)
                .toList();
    }

    /** Projects where {@code userId} holds {@link ProjectRole#CAN_MANAGE}. */
    public List<ProjectId> managedProjects(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        return jdbc.sql("""
                        select project_id
                        from role_assignments
                        where user_id = ?
                          and role = ?
                        order by project_id
                        """)
                .param(userId.value())
                .param(ProjectRole.CAN_MANAGE.name())
                .query((rs, row) -> new ProjectId(rs.getObject("project_id", java.util.UUID.class)))
                .list();
    }

    /** Projects where {@code userId} is the only non-deleted {@link ProjectRole#CAN_MANAGE} member. */
    public List<ProjectId> soleManagerProjects(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        return jdbc.sql("""
                        select ra.project_id
                        from role_assignments ra
                        where ra.user_id = ?
                          and ra.role = ?
                          and not exists (
                            select 1
                            from role_assignments other
                            join users u on u.user_id = other.user_id
                            where other.project_id = ra.project_id
                              and other.role = ?
                              and other.user_id <> ra.user_id
                              and u.deleted_at is null
                          )
                        order by ra.project_id
                        """)
                .param(userId.value())
                .param(ProjectRole.CAN_MANAGE.name())
                .param(ProjectRole.CAN_MANAGE.name())
                .query((rs, row) -> new ProjectId(rs.getObject("project_id", java.util.UUID.class)))
                .list();
    }

    public List<ProjectId> projectsForUser(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        return jdbc.sql("""
                        select project_id
                        from role_assignments
                        where user_id = ?
                        order by project_id
                        """)
                .param(userId.value())
                .query((rs, row) -> new ProjectId(rs.getObject("project_id", java.util.UUID.class)))
                .list();
    }

    public int removeAllForUser(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        return jdbc.sql("delete from role_assignments where user_id = ?")
                .param(userId.value())
                .update();
    }

    private static Optional<RoleAssignment> assignment(ProjectId projectId, ResultSet rs) throws SQLException {
        UserId userId = new UserId(rs.getString("user_id"));
        return parse(rs.getString("role")).map(role -> new RoleAssignment(projectId, userId, role));
    }

    /** An unknown value denies access. The stored text stays out of the exception message. */
    private static Optional<ProjectRole> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            log.warn("ignoring a role assignment with a blank role");
            return Optional.empty();
        }
        try {
            return Optional.of(ProjectRole.valueOf(stored));
        } catch (IllegalArgumentException ex) {
            log.warn("ignoring a role assignment with an unknown role");
            return Optional.empty();
        }
    }
}
