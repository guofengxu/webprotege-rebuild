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

    private static final Map<String, List<String>> PRIMARY_KEYS = Map.ofEntries(
            Map.entry("users", List.of("user_id")),
            Map.entry("api_keys", List.of("key_id")),
            Map.entry("projects", List.of("project_id")),
            Map.entry("role_assignments", List.of("project_id", "user_id")),
            Map.entry("project_settings", List.of("project_id", "key")),
            Map.entry("revisions", List.of("project_id", "rev_no")),
            Map.entry("revision_subjects", List.of("project_id", "subject_iri", "rev_no")),
            Map.entry("snapshots", List.of("project_id", "rev_no")),
            Map.entry("outbox", List.of("id")),
            Map.entry("outbox_consumers", List.of("consumer")),
            Map.entry("project_webhooks", List.of("webhook_id")),
            Map.entry("webhook_deliveries", List.of("delivery_id")),
            Map.entry("user_identities", List.of("issuer", "subject")),
            Map.entry("project_form", List.of("project_id", "form_id")),
            Map.entry("project_form_selector", List.of("project_id", "form_id")));

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
