package wprebuild.app.revision;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;

/**
 * Inserts {@code REVISION_COMMITTED} with {@code {projectId, revNo, userId, ts, changeCount}}.
 *
 * <p>Takes a global transaction advisory lock before the insert so identity order matches commit
 * order across projects (P6-08). Wakes {@link OutboxRelay} after commit via {@link ObjectProvider}
 * to avoid a constructor cycle with projection consumers.
 */
@Component
final class JdbcRevisionCommitOutbox implements RevisionCommitOutbox {

    /**
     * Global {@code pg_advisory_xact_lock} key for outbox inserts. Serialises only the
     * insert-to-commit window so {@code outbox.id} order equals commit order; must never be held
     * while waiting on a project generation lock (ADR-006).
     */
    static final long OUTBOX_COMMIT_ORDER_LOCK = 0x5750_4F42_5F49_4431L; // WP_OB_ID1

    private final JdbcClient jdbc;

    private final ObjectProvider<OutboxRelay> relay;

    JdbcRevisionCommitOutbox(JdbcClient jdbc, ObjectProvider<OutboxRelay> relay) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    @Override
    public void record(ProjectId projectId, Revision revision) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(revision, "revision");
        // void result: map the row without reading the column
        jdbc.sql("select pg_advisory_xact_lock(?)")
                .param(OUTBOX_COMMIT_ORDER_LOCK)
                .query((rs, rowNum) -> Boolean.TRUE)
                .single();
        jdbc.sql("""
                        insert into outbox (project_id, rev_no, type, payload)
                        values (
                            ?,
                            ?,
                            'REVISION_COMMITTED',
                            jsonb_build_object(
                                'projectId', cast(? as text),
                                'revNo', cast(? as bigint),
                                'userId', cast(? as text),
                                'ts', cast(? as text),
                                'changeCount', cast(? as integer)
                            )
                        )
                        """)
                .param(projectId.value())
                .param(revision.number().value())
                .param(projectId.toString())
                .param(revision.number().value())
                .param(revision.user().value())
                .param(JdbcRevisionLog.storedTimestamp(revision).toString())
                .param(revision.changes().size())
                .update();
        wakeAfterCommit();
    }

    private void wakeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            wakeRelay();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wakeRelay();
            }
        });
    }

    private void wakeRelay() {
        OutboxRelay outboxRelay = relay.getIfAvailable();
        if (outboxRelay != null) {
            outboxRelay.wake();
        }
    }
}
