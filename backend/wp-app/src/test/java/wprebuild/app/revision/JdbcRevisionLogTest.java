package wprebuild.app.revision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.support.TestData;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.revision.RevisionLogContract;

/**
 * PostgreSQL adapter against {@link RevisionLogContract}. The contract mints random project ids, so
 * each append first inserts the user and project rows the foreign key requires.
 */
@DatabaseTest
class JdbcRevisionLogTest extends RevisionLogContract {

    private final JdbcRevisionLog log;
    private final TestData data;
    private final PlatformTransactionManager transactions;

    JdbcRevisionLogTest(JdbcRevisionLog log, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.log = log;
        this.data = new TestData(jdbc);
        this.transactions = transactions;
    }

    @Test
    void read_stream_remains_open_after_the_surrounding_transaction_commits() {
        ProjectId projectId = ProjectId.random();
        data.insertProject(projectId, UserId.SYSTEM);
        Revision stored = revision(1, "held");
        log.append(projectId, RevisionNumber.ZERO, stored);

        TransactionTemplate transaction = new TransactionTemplate(transactions);
        Stream<Revision> opened =
                transaction.execute(status -> log.read(projectId, RevisionNumber.of(1), RevisionNumber.of(1)));

        assertThat(opened).isNotNull();
        try (Stream<Revision> stream = opened) {
            assertThat(stream.toList()).containsExactly(stored);
        }
    }

    @Override
    protected RevisionLog newLog() {
        return new RevisionLog() {
            @Override
            public RevisionNumber head(ProjectId projectId) {
                return log.head(projectId);
            }

            @Override
            public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
                data.insertProject(projectId, UserId.SYSTEM);
                log.append(projectId, expectedHead, revision);
            }

            @Override
            public Optional<Revision> get(ProjectId projectId, RevisionNumber number) {
                return log.get(projectId, number);
            }

            @Override
            public Stream<Revision> read(
                    ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive) {
                return log.read(projectId, fromInclusive, toInclusive);
            }
        };
    }
}
