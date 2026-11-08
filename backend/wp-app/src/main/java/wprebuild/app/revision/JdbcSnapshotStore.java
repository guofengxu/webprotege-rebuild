package wprebuild.app.revision;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.SnapshotStore;
import wprebuild.kernel.revision.StoredSnapshot;

/** PostgreSQL {@link SnapshotStore}. Payloads are copied on the way in and out. */
@Component
public final class JdbcSnapshotStore implements SnapshotStore {

    private final JdbcClient jdbc;

    public JdbcSnapshotStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void save(ProjectId projectId, RevisionNumber revision, String codecId, byte[] payload) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(codecId, "codec id");
        Objects.requireNonNull(payload, "payload");
        byte[] stored = payload.clone();
        jdbc.sql("""
                        insert into snapshots (project_id, rev_no, codec, payload)
                        values (?, ?, ?, ?)
                        on conflict (project_id, rev_no) do update
                        set codec = excluded.codec,
                            payload = excluded.payload,
                            created_at = now()
                        """)
                .param(requireProject(projectId).value())
                .param(revision.value())
                .param(codecId)
                .param(stored)
                .update();
    }

    @Override
    public Optional<StoredSnapshot> latest(ProjectId projectId) {
        return jdbc.sql("""
                        select rev_no, codec, payload
                        from snapshots
                        where project_id = ?
                        order by rev_no desc
                        limit 1
                        """)
                .param(requireProject(projectId).value())
                .query(this::mapSnapshot)
                .optional();
    }

    @Override
    public Optional<StoredSnapshot> latestAtOrBefore(ProjectId projectId, RevisionNumber revision) {
        Objects.requireNonNull(revision, "revision");
        return jdbc.sql("""
                        select rev_no, codec, payload
                        from snapshots
                        where project_id = ? and rev_no <= ?
                        order by rev_no desc
                        limit 1
                        """)
                .param(requireProject(projectId).value())
                .param(revision.value())
                .query(this::mapSnapshot)
                .optional();
    }

    private StoredSnapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        byte[] payload = rs.getBytes("payload");
        return new StoredSnapshot(RevisionNumber.of(rs.getLong("rev_no")), rs.getString("codec"), payload);
    }

    private static ProjectId requireProject(ProjectId projectId) {
        return Objects.requireNonNull(projectId, "project id");
    }
}
