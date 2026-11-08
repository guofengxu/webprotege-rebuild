package wprebuild.app.support;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Inserts the user and project rows that revision and snapshot foreign keys require.
 *
 * <p>Later cards reuse this helper. Repeated inserts of the same id do nothing, so concurrent tests
 * can provision one project from several threads.
 */
public final class TestData {

    private final JdbcClient jdbc;

    public TestData(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void insertUser(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        jdbc.sql("""
                        insert into users (user_id, display_name)
                        values (?, ?)
                        on conflict (user_id) do nothing
                        """).param(userId.value()).param(userId.value()).update();
    }

    public void insertProject(ProjectId projectId, UserId owner) {
        Objects.requireNonNull(projectId, "project id");
        insertUser(owner);
        jdbc.sql("""
                        insert into projects (
                            project_id, display_name, owner, created_at, created_by, modified_at, modified_by
                        ) values (?, 'Test', ?, now(), ?, now(), ?)
                        on conflict (project_id) do nothing
                        """)
                .param(projectId.value())
                .param(owner.value())
                .param(owner.value())
                .param(owner.value())
                .update();
    }
}
