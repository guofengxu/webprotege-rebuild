package wprebuild.app.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.app.bus.ProjectExistenceCheck;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** PostgreSQL {@code projects}, and the bus check for whether that row can accept a command. */
@Repository
public class JdbcProjectRepository implements ProjectRepository, ProjectExistenceCheck {

    private static final Logger log = LoggerFactory.getLogger(JdbcProjectRepository.class);

    private static final String COLUMNS = """
            project_id, display_name, description, owner, in_trash,
            created_at, created_by, modified_at, modified_by
            """;

    private final JdbcClient jdbc;

    public JdbcProjectRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(ProjectDetails details) {
        Objects.requireNonNull(details, "details");
        int stored = jdbc.sql("""
                        insert into projects (
                            project_id, display_name, description, owner, in_trash,
                            created_at, created_by, modified_at, modified_by
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(details.projectId().value())
                .param(details.displayName())
                .param(details.description())
                .param(details.owner().value())
                .param(details.inTrash())
                .param(stored(details.createdAt()))
                .param(details.createdBy().value())
                .param(stored(details.modifiedAt()))
                .param(details.modifiedBy().value())
                .update();
        if (stored != 1) {
            throw new IllegalStateException("project was not stored");
        }
    }

    @Override
    public Optional<ProjectDetails> find(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return jdbc.sql("select " + COLUMNS + " from projects where project_id = ?")
                .param(projectId.value())
                .query(this::map)
                .optional();
    }

    @Override
    public List<ProjectDetails> listFor(UserId userId, boolean trashed) {
        Objects.requireNonNull(userId, "user id");
        return List.copyOf(jdbc.sql("select " + COLUMNS + """
                         from projects
                         where in_trash = ?
                           and exists (
                             select 1 from role_assignments
                             where role_assignments.project_id = projects.project_id
                               and role_assignments.user_id = ?
                           )
                         order by display_name, project_id
                        """)
                .param(trashed)
                .param(userId.value())
                .query(this::map)
                .list());
    }

    @Override
    public List<ProjectDetails> listAll(boolean trashed) {
        return List.copyOf(jdbc.sql("select " + COLUMNS + """
                         from projects
                         where in_trash = ?
                         order by display_name, project_id
                        """)
                .param(trashed)
                .query(this::map)
                .list());
    }

    @Override
    public ProjectDetails updateDetails(
            ProjectId projectId, String displayName, String description, UserId modifiedBy, Instant modifiedAt) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(modifiedBy, "modified by");
        String name = ProjectText.displayName(displayName);
        String text = ProjectText.description(description);
        int updated = jdbc.sql("""
                        update projects
                        set display_name = ?, description = ?, modified_at = ?, modified_by = ?
                        where project_id = ? and in_trash = false
                        """)
                .param(name)
                .param(text)
                .param(stored(modifiedAt))
                .param(modifiedBy.value())
                .param(projectId.value())
                .update();
        if (updated == 0) {
            throw inactive(projectId);
        }
        return find(projectId).orElseThrow(() -> new NotFoundException("project not found"));
    }

    @Override
    public ProjectDetails setTrash(ProjectId projectId, boolean inTrash, UserId modifiedBy, Instant modifiedAt) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(modifiedBy, "modified by");
        int updated = jdbc.sql("""
                        update projects
                        set in_trash = ?, modified_at = ?, modified_by = ?
                        where project_id = ? and in_trash = ?
                        """)
                .param(inTrash)
                .param(stored(modifiedAt))
                .param(modifiedBy.value())
                .param(projectId.value())
                .param(!inTrash)
                .update();
        if (updated == 0) {
            if (!inTrash && state(projectId) == ProjectState.ACTIVE) {
                return find(projectId).orElseThrow(() -> new NotFoundException("project not found"));
            }
            throw inactive(projectId);
        }
        return find(projectId).orElseThrow(() -> new NotFoundException("project not found"));
    }

    @Override
    public void recordModification(ProjectId projectId, UserId modifiedBy, Instant modifiedAt) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(modifiedBy, "modified by");
        int updated = jdbc.sql("""
                        update projects
                        set modified_at = ?, modified_by = ?
                        where project_id = ?
                        """)
                .param(stored(modifiedAt))
                .param(modifiedBy.value())
                .param(projectId.value())
                .update();
        if (updated == 0) {
            log.warn("revision committed for missing project {}", projectId);
        }
    }

    @Override
    public ProjectState state(ProjectId projectId) {
        return find(projectId)
                .map(details -> details.inTrash() ? ProjectState.TRASHED : ProjectState.ACTIVE)
                .orElse(ProjectState.MISSING);
    }

    /** Same code the command bus uses when it rejects a trashed project. */
    private RuntimeException inactive(ProjectId projectId) {
        return switch (state(projectId)) {
            case MISSING -> new NotFoundException("project not found");
            case TRASHED -> new ConflictException("PROJECT_TRASHED", "project is in the trash");
            case ACTIVE -> new IllegalStateException("project " + projectId + " changed before the update");
        };
    }

    private ProjectDetails map(ResultSet rs, int row) throws SQLException {
        return new ProjectDetails(
                new ProjectId(rs.getObject("project_id", UUID.class)),
                rs.getString("display_name"),
                rs.getString("description"),
                new UserId(rs.getString("owner")),
                rs.getBoolean("in_trash"),
                instant(rs, "created_at"),
                new UserId(rs.getString("created_by")),
                instant(rs, "modified_at"),
                new UserId(rs.getString("modified_by")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new IllegalStateException(column + " is null");
        }
        return value.toInstant();
    }

    private static OffsetDateTime stored(Instant instant) {
        return Objects.requireNonNull(instant, "timestamp")
                .truncatedTo(ChronoUnit.MICROS)
                .atOffset(ZoneOffset.UTC);
    }
}
