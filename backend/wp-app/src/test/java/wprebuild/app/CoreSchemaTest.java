package wprebuild.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.support.DatabaseTest;

@DatabaseTest
@Transactional
class CoreSchemaTest {

    private static final Map<String, List<String>> PRIMARY_KEYS = Map.of(
            "users", List.of("user_id"),
            "api_keys", List.of("key_id"),
            "projects", List.of("project_id"),
            "role_assignments", List.of("project_id", "user_id"),
            "project_settings", List.of("project_id", "key"),
            "revisions", List.of("project_id", "rev_no"),
            "revision_subjects", List.of("project_id", "subject_iri", "rev_no"),
            "snapshots", List.of("project_id", "rev_no"),
            "outbox", List.of("id"),
            "outbox_consumers", List.of("consumer"));

    private final JdbcClient jdbcClient;

    CoreSchemaTest(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Test
    void every_core_table_and_primary_key_exists() {
        List<String> tables = jdbcClient.sql("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_type = 'BASE TABLE'
                        """).query(String.class).list();
        assertThat(tables).containsAll(PRIMARY_KEYS.keySet());

        List<KeyColumn> keyColumns = jdbcClient
                .sql("""
                        select tc.table_name, kcu.column_name
                        from information_schema.table_constraints tc
                        join information_schema.key_column_usage kcu
                          on tc.constraint_schema = kcu.constraint_schema
                         and tc.constraint_name = kcu.constraint_name
                         and tc.table_name = kcu.table_name
                        where tc.table_schema = 'public'
                          and tc.constraint_type = 'PRIMARY KEY'
                        order by tc.table_name, kcu.ordinal_position
                        """)
                .query((rs, rowNum) -> new KeyColumn(rs.getString("table_name"), rs.getString("column_name")))
                .list();

        for (var expected : PRIMARY_KEYS.entrySet()) {
            List<String> actual = keyColumns.stream()
                    .filter(column -> column.tableName().equals(expected.getKey()))
                    .map(KeyColumn::columnName)
                    .toList();
            assertThat(actual).as("primary key of %s", expected.getKey()).isEqualTo(expected.getValue());
        }
    }

    @Test
    void revisions_reject_rev_no_zero() {
        String userId = "schema-" + UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbcClient
                .sql("insert into users (user_id, display_name) values (?, ?)")
                .param(userId)
                .param("Schema Tester")
                .update();
        jdbcClient
                .sql("""
                        insert into projects (
                            project_id, display_name, owner, created_at, created_by, modified_at, modified_by
                        ) values (?, 'Schema', ?, now(), ?, now(), ?)
                        """)
                .param(projectId)
                .param(userId)
                .param(userId)
                .param(userId)
                .update();

        assertThatThrownBy(() -> jdbcClient
                        .sql("""
                                insert into revisions (
                                    project_id, rev_no, user_id, ts, description, change_count, codec, payload
                                ) values (?, 0, ?, now(), 'invalid', 0, 'ofn-lines', ?)
                                """)
                        .param(projectId)
                        .param(userId)
                        .param(new byte[] {1})
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private record KeyColumn(String tableName, String columnName) {}
}
