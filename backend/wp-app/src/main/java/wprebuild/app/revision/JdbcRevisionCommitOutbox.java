package wprebuild.app.revision;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;

/** Inserts {@code REVISION_COMMITTED} with {@code {projectId, revNo, userId, ts, changeCount}}. */
@Component
final class JdbcRevisionCommitOutbox implements RevisionCommitOutbox {

    private final JdbcClient jdbc;

    JdbcRevisionCommitOutbox(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(ProjectId projectId, Revision revision) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(revision, "revision");
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
    }
}
