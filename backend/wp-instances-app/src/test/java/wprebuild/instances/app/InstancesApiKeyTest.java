package wprebuild.instances.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Instance-store API keys are independent of the editor access module (P6-58).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InstancesApiKeyTest {

    private static final String INSTANCES_DB = "wp_instances_api_keys";
    private static final String PROJECTION_DB = "wp_projection_api_keys";
    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String PEPPER = "test-instances-pepper";
    private static final String BOOTSTRAP_KEY = "wpi_bootstrap_test_key_do_not_reuse";
    private static final String API_KEY_HEADER = "X-API-Key";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("instancesDataSource")
    private DataSource instancesDataSource;

    @Autowired
    private InstancesApiKeyService apiKeys;

    @BeforeAll
    static void createDatabases() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + INSTANCES_DB);
            statement.execute("create database " + PROJECTION_DB);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("wp.instances.project-id", PROJECT_ID::toString);
        registry.add("wp.instances.api-key-pepper", () -> PEPPER);
        registry.add("wp.instances.bootstrap-api-key", () -> BOOTSTRAP_KEY);
        registry.add("wp.instances.datasource.url", () -> jdbcUrl(INSTANCES_DB));
        registry.add("wp.instances.datasource.username", postgres::getUsername);
        registry.add("wp.instances.datasource.password", postgres::getPassword);
        registry.add("wp.projection.datasource.url", () -> jdbcUrl(PROJECTION_DB));
        registry.add("wp.projection.datasource.username", postgres::getUsername);
        registry.add("wp.projection.datasource.password", postgres::getPassword);
    }

    @Test
    void missing_api_key_returns_401() {
        ResponseEntity<Void> response = client().get()
                .uri(pingUri(PROJECT_ID))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, body) -> {})
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void wrong_api_key_returns_401() {
        ResponseEntity<Void> response = client().get()
                .uri(pingUri(PROJECT_ID))
                .header(API_KEY_HEADER, "not-the-bootstrap-key")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, body) -> {})
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void valid_bootstrap_key_allows_ping_for_configured_project() {
        ResponseEntity<Void> response = client().get()
                .uri(pingUri(PROJECT_ID))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .retrieve()
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void wrong_project_id_returns_403_when_authenticated() {
        ResponseEntity<Void> response = client().get()
                .uri(pingUri(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, body) -> {})
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void revoked_key_returns_401() {
        // Dedicated key so revoking does not break sibling tests that use the bootstrap key.
        String dedicatedPlaintext = "wpi_revoke_only_key_for_test";
        UUID keyId = UUID.randomUUID();
        jdbc().update("""
                insert into api_keys (id, name, key_hash, created_at, revoked_at)
                values (?, ?, ?, now(), null)
                """, keyId, "revoke-test", apiKeys.hashForOps(dedicatedPlaintext));
        apiKeys.revoke(keyId);

        ResponseEntity<Void> response = client().get()
                .uri(pingUri(PROJECT_ID))
                .header(API_KEY_HEADER, dedicatedPlaintext)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, body) -> {})
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void plaintext_is_not_stored_in_database() {
        List<Map<String, Object>> rows = jdbc().queryForList("select name, key_hash, revoked_at from api_keys");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            assertThat(row.get("key_hash")).isInstanceOf(String.class);
            String hash = (String) row.get("key_hash");
            assertThat(hash).doesNotContain(BOOTSTRAP_KEY);
            assertThat(hash).isNotEqualTo(BOOTSTRAP_KEY);
            assertThat(hash).hasSize(64);
            assertThat(hash).matches("[0-9a-f]{64}");
        }
        Integer plaintextHits = jdbc().queryForObject("""
                select count(*) from api_keys
                where key_hash = ? or name = ? or cast(id as text) = ?
                """, Integer.class, BOOTSTRAP_KEY, BOOTSTRAP_KEY, BOOTSTRAP_KEY);
        assertThat(plaintextHits).isZero();
    }

    @Test
    void liveness_remains_anonymous() {
        ResponseEntity<Void> response = client().get()
                .uri("http://localhost:" + port + "/actuator/health/liveness")
                .retrieve()
                .toBodilessEntity();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private RestClient client() {
        return RestClient.create();
    }

    private String pingUri(UUID projectId) {
        return "http://localhost:" + port + "/api/v1/projects/" + projectId + "/ping";
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(instancesDataSource);
    }

    private static String jdbcUrl(String database) {
        return postgres.getJdbcUrl().replaceFirst("/[^/]+$", "/" + database);
    }
}
