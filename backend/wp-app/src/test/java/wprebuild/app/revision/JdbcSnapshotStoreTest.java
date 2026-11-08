package wprebuild.app.revision;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.support.TestData;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.SnapshotStore;
import wprebuild.kernel.revision.SnapshotStoreContract;
import wprebuild.kernel.revision.StoredSnapshot;

/** PostgreSQL adapter against {@link SnapshotStoreContract}. */
@DatabaseTest
class JdbcSnapshotStoreTest extends SnapshotStoreContract {

    private final JdbcSnapshotStore store;
    private final TestData data;

    JdbcSnapshotStoreTest(JdbcSnapshotStore store, JdbcClient jdbc) {
        this.store = store;
        this.data = new TestData(jdbc);
    }

    @Override
    protected SnapshotStore newStore() {
        return new SnapshotStore() {
            @Override
            public void save(ProjectId projectId, RevisionNumber revision, String codecId, byte[] payload) {
                data.insertProject(projectId, UserId.SYSTEM);
                store.save(projectId, revision, codecId, payload);
            }

            @Override
            public Optional<StoredSnapshot> latest(ProjectId projectId) {
                return store.latest(projectId);
            }

            @Override
            public Optional<StoredSnapshot> latestAtOrBefore(ProjectId projectId, RevisionNumber revision) {
                return store.latestAtOrBefore(projectId, revision);
            }
        };
    }
}
