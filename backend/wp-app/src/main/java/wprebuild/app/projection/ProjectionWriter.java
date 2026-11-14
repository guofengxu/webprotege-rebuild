package wprebuild.app.projection;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import wprebuild.app.projection.ProjectionSnapshot.DomainRow;
import wprebuild.app.projection.ProjectionSnapshot.EntityRow;
import wprebuild.app.projection.ProjectionSnapshot.HierarchyEdgeRow;
import wprebuild.app.projection.ProjectionSnapshot.LabelRow;
import wprebuild.app.projection.ProjectionSnapshot.RangeRow;
import wprebuild.kernel.id.ProjectId;

/**
 * Replaces one project's projection rows under a per-project transaction advisory lock. Joins an
 * existing transaction when present (P6-10 / P6-12); does not open its own boundary so {@link
 * #lockProject} and {@link #replaceProject} can share one lock window.
 *
 * <p>Not {@code final}: Spring may subclass for proxies.
 */
@Component
public class ProjectionWriter {

    /** Batch size for JDBC inserts; kept under PostgreSQL's 65535-parameter limit. */
    static final int BATCH_SIZE = 1000;

    /**
     * Salt mixed into the per-project {@code pg_advisory_xact_lock(bigint)} key so projection locks
     * stay distinct from the outbox commit-order lock
     * ({@code JdbcRevisionCommitOutbox.OUTBOX_COMMIT_ORDER_LOCK}).
     */
    static final long PROJECTION_LOCK_SALT = 0x5052_4F4A_5F4C_4F43L; // PROJ_LOC

    /** Transaction-bound set of project ids locked via {@link #lockProject} in the current TX. */
    private static final Object LOCKED_PROJECTS_RESOURCE = new Object();

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ProjectionWriter(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Takes the per-project {@code pg_advisory_xact_lock} for projection writes. Must be called
     * inside an active transaction; all writers for a project share this lock.
     */
    public void lockProject(ProjectId projectId) {
        ProjectId id = requireProject(projectId);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("lockProject requires an active transaction");
        }
        jdbc.sql("select pg_advisory_xact_lock(?)")
                .param(advisoryKey(id))
                .query((rs, rowNum) -> Boolean.TRUE)
                .single();
        lockedProjects().add(id.value());
    }

    /**
     * Deletes the project's projection rows and inserts {@code snapshot}. Returns {@code false}
     * when {@code revNo} is less than the stored {@code project_state.rev_no} (projection only moves
     * forward). Requires {@link #lockProject} in the same transaction.
     */
    public boolean replaceProject(ProjectId projectId, ProjectionSnapshot snapshot, long revNo) {
        ProjectId id = requireProject(projectId);
        Objects.requireNonNull(snapshot, "snapshot");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("replaceProject requires an active transaction");
        }
        if (!lockedProjects().contains(id.value())) {
            throw new IllegalStateException("replaceProject requires lockProject in the same transaction");
        }
        Optional<Long> current = jdbc.sql("select rev_no from projection.project_state where project_id = ?")
                .param(id.value())
                .query(Long.class)
                .optional();
        if (current.isPresent() && revNo < current.get()) {
            return false;
        }
        deleteProjectRows(id);
        insertEntities(id, snapshot.entities());
        insertLabels(id, snapshot.labels());
        insertHierarchyEdges(id, snapshot.hierarchyEdges());
        insertDomains(id, snapshot.domains());
        insertRanges(id, snapshot.ranges());
        upsertProjectState(id, revNo);
        return true;
    }

    private void deleteProjectRows(ProjectId projectId) {
        UUID id = projectId.value();
        jdbc.sql("delete from projection.entities where project_id = ?")
                .param(id)
                .update();
        jdbc.sql("delete from projection.labels where project_id = ?").param(id).update();
        jdbc.sql("delete from projection.hierarchy_edges where project_id = ?")
                .param(id)
                .update();
        jdbc.sql("delete from projection.property_domains where project_id = ?")
                .param(id)
                .update();
        jdbc.sql("delete from projection.property_ranges where project_id = ?")
                .param(id)
                .update();
    }

    private void insertEntities(ProjectId projectId, List<EntityRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        insert into projection.entities (project_id, iri, entity_type, deprecated)
                        values (?, ?, ?, ?)
                        """, rows, BATCH_SIZE, (statement, row) -> {
            statement.setObject(1, projectId.value());
            statement.setString(2, row.iri());
            statement.setString(3, row.entityType());
            statement.setBoolean(4, row.deprecated());
        });
    }

    private void insertLabels(ProjectId projectId, List<LabelRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        insert into projection.labels (project_id, iri, property_iri, lang, value)
                        values (?, ?, ?, ?, ?)
                        """, rows, BATCH_SIZE, (statement, row) -> {
            statement.setObject(1, projectId.value());
            statement.setString(2, row.iri());
            statement.setString(3, row.propertyIri());
            statement.setString(4, row.lang());
            statement.setString(5, row.value());
        });
    }

    private void insertHierarchyEdges(ProjectId projectId, List<HierarchyEdgeRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        insert into projection.hierarchy_edges (project_id, hierarchy, child_iri, parent_iri)
                        values (?, ?, ?, ?)
                        """, rows, BATCH_SIZE, (statement, row) -> {
            statement.setObject(1, projectId.value());
            statement.setString(2, row.hierarchy());
            statement.setString(3, row.childIri());
            statement.setString(4, row.parentIri());
        });
    }

    private void insertDomains(ProjectId projectId, List<DomainRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        insert into projection.property_domains (project_id, property_iri, class_iri)
                        values (?, ?, ?)
                        """, rows, BATCH_SIZE, (statement, row) -> {
            statement.setObject(1, projectId.value());
            statement.setString(2, row.propertyIri());
            statement.setString(3, row.classIri());
        });
    }

    private void insertRanges(ProjectId projectId, List<RangeRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        insert into projection.property_ranges (project_id, property_iri, range_iri, range_kind)
                        values (?, ?, ?, ?)
                        """, rows, BATCH_SIZE, (statement, row) -> {
            statement.setObject(1, projectId.value());
            statement.setString(2, row.propertyIri());
            statement.setString(3, row.rangeIri());
            statement.setString(4, row.rangeKind());
        });
    }

    private void upsertProjectState(ProjectId projectId, long revNo) {
        jdbc.sql("""
                        insert into projection.project_state (project_id, rev_no, rebuilt_at, updated_at)
                        values (?, ?, now(), now())
                        on conflict (project_id) do update set
                            rev_no = excluded.rev_no,
                            rebuilt_at = excluded.rebuilt_at,
                            updated_at = excluded.updated_at
                        """).param(projectId.value()).param(revNo).update();
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> lockedProjects() {
        Set<UUID> locked = (Set<UUID>) TransactionSynchronizationManager.getResource(LOCKED_PROJECTS_RESOURCE);
        if (locked == null) {
            locked = new HashSet<>();
            TransactionSynchronizationManager.bindResource(LOCKED_PROJECTS_RESOURCE, locked);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(LOCKED_PROJECTS_RESOURCE);
                }
            });
        }
        return locked;
    }

    /**
     * Per-project advisory lock key: both UUID halves mixed with {@link #PROJECTION_LOCK_SALT} so
     * keys stay in a distinct space from the global outbox lock.
     */
    static long advisoryKey(ProjectId projectId) {
        UUID value = projectId.value();
        return (value.getMostSignificantBits() ^ Long.rotateLeft(value.getLeastSignificantBits(), 1))
                ^ PROJECTION_LOCK_SALT;
    }

    private static ProjectId requireProject(ProjectId projectId) {
        return Objects.requireNonNull(projectId, "project id");
    }
}
