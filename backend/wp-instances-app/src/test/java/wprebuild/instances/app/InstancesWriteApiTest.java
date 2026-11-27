package wprebuild.instances.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import wprebuild.instances.rules.ProjectionContract;

/** Batch write API acceptance tests (P6-59). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InstancesWriteApiTest {

    private static final String INSTANCES_DB = "wp_instances_write_api";
    private static final String PROJECTION_DB = "wp_projection_write_api";
    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String PEPPER = "test-instances-write-pepper";
    private static final String BOOTSTRAP_KEY = "wpi_bootstrap_write_test_key";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String NS = "http://example.org/wp-test/warehouse#";
    private static final String PALLET = NS + "Pallet";
    private static final String STORAGE_LOCATION = NS + "StorageLocation";
    private static final String LOCATION = NS + "Location";
    private static final String HANDLING_UNIT = NS + "HandlingUnit";
    private static final String STORED_AT = NS + "storedAt";
    private static final String CODE = NS + "code";
    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-01T11:00:00Z");
    private static final Instant EARLY = Instant.parse("2026-03-01T09:00:00Z");
    private static final long REV_NO = 42L;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_BODY =
            new ParameterizedTypeReference<>() {};

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("instancesDataSource")
    private DataSource instancesDataSource;

    @BeforeAll
    static void createDatabasesAndSeedProjection() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + INSTANCES_DB);
            statement.execute("create database " + PROJECTION_DB);
        }
        // Projection DS is read-only at runtime; seed via a direct writable connection.
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl(PROJECTION_DB), postgres.getUsername(), postgres.getPassword())) {
            applyProjectionSchema(connection);
            seedProjection(connection);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("wp.instances.project-id", PROJECT_ID::toString);
        registry.add("wp.instances.api-key-pepper", () -> PEPPER);
        registry.add("wp.instances.bootstrap-api-key", () -> BOOTSTRAP_KEY);
        registry.add("wp.instances.max-batch-ops", () -> "3");
        registry.add("wp.instances.max-request-bytes", () -> "2048");
        registry.add("wp.instances.max-future-skew", () -> "PT5M");
        registry.add("wp.instances.datasource.url", () -> jdbcUrl(INSTANCES_DB));
        registry.add("wp.instances.datasource.username", postgres::getUsername);
        registry.add("wp.instances.datasource.password", postgres::getPassword);
        registry.add("wp.projection.datasource.url", () -> jdbcUrl(PROJECTION_DB));
        registry.add("wp.projection.datasource.username", postgres::getUsername);
        registry.add("wp.projection.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void truncateInstances() {
        jdbc().update("truncate links, instances");
    }

    @Test
    void missing_api_key_returns_401() {
        ResponseEntity<Void> response = client().post()
                .uri(batchUri(PROJECT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .body(upsertBody(T0, NS + "p1", PALLET, "{}"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, body) -> {})
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void wrong_project_id_returns_403() {
        ResponseEntity<Void> response = client().post()
                .uri(batchUri(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(upsertBody(T0, NS + "p1", PALLET, "{}"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, body) -> {})
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(countInstances()).isZero();
    }

    @Test
    void successful_upsert_returns_200_and_persists() {
        ResponseEntity<Map<String, Object>> response = client().post()
                .uri(batchUri(PROJECT_ID))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(upsertBody(T0, NS + "p1", PALLET, "{}"))
                .retrieve()
                .toEntity(MAP_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("applied", 1);
        assertThat(((Number) response.getBody().get("ontologyRev")).longValue()).isEqualTo(REV_NO);
        assertThat(response.getBody()).containsEntry("source", "bootstrap");
        assertThat(countInstances()).isEqualTo(1);
    }

    @Test
    void validation_failure_returns_422_and_writes_nothing() {
        String body = """
                {
                  "at": "%s",
                  "ops": [
                    {"op":"upsert","iri":"%s","classIri":"%s","attributesJson":"{}"},
                    {"op":"upsert","iri":"%s","classIri":"%s","attributesJson":"{}"}
                  ]
                }
                """.formatted(T0, NS + "ok", PALLET, NS + "bad", NS + "NoSuchClass");
        ResponseEntity<Map<String, Object>> response = client().post()
                .uri(batchUri(PROJECT_ID))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, respBody) -> {})
                .toEntity(MAP_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsKey("violations");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) response.getBody().get("violations");
        assertThat(violations).isNotEmpty();
        assertThat(countInstances()).isZero();
    }

    @Test
    void early_clock_returns_400_and_keeps_prior_row() {
        assertThat(client().post()
                        .uri(batchUri(PROJECT_ID))
                        .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(upsertBody(T0, NS + "p2", PALLET, "{}"))
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        // Same content with an earlier at rewrites history → 400; prior row kept.
        ResponseEntity<Map<String, Object>> response = client().post()
                .uri(batchUri(PROJECT_ID))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(upsertBody(EARLY, NS + "p2", PALLET, "{}"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, respBody) -> {})
                .toEntity(MAP_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(countInstances()).isEqualTo(1);
    }

    @Test
    void far_future_at_returns_400() {
        Instant far = Instant.now().plusSeconds(3600);
        ResponseEntity<Map<String, Object>> response = client().post()
                .uri(batchUri(PROJECT_ID))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(upsertBody(far, NS + "p3", PALLET, "{}"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, respBody) -> {})
                .toEntity(MAP_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(countInstances()).isZero();
    }

    @Test
    void too_many_ops_returns_413() {
        String body = """
                {
                  "at": "%s",
                  "ops": [
                    {"op":"upsert","iri":"%sa","classIri":"%s","attributesJson":"{}"},
                    {"op":"upsert","iri":"%sb","classIri":"%s","attributesJson":"{}"},
                    {"op":"upsert","iri":"%sc","classIri":"%s","attributesJson":"{}"},
                    {"op":"upsert","iri":"%sd","classIri":"%s","attributesJson":"{}"}
                  ]
                }
                """.formatted(T0, NS, PALLET, NS, PALLET, NS, PALLET, NS, PALLET);
        ResponseEntity<Map<String, Object>> response = client().post()
                .uri(batchUri(PROJECT_ID))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, respBody) -> {})
                .toEntity(MAP_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(countInstances()).isZero();
    }

    @Test
    void oversized_request_body_returns_413() {
        // max-request-bytes is 2048 in this test; pad attributesJson so the HTTP body exceeds it.
        String padding = "x".repeat(2500);
        String body = """
                {
                  "at": "%s",
                  "ops": [
                    {"op":"upsert","iri":"%s","classIri":"%s","attributesJson":"{\\"pad\\":\\"%s\\"}"}
                  ]
                }
                """.formatted(T0, NS + "big", PALLET, padding);
        assertThat(body.length()).isGreaterThan(2048);
        ResponseEntity<Void> response = client().post()
                .uri(batchUri(PROJECT_ID))
                .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, respBody) -> {})
                .toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(countInstances()).isZero();
    }

    @Test
    void link_and_retire_round_trip() {
        String setup = """
                {
                  "at": "%s",
                  "ops": [
                    {"op":"upsert","iri":"%s","classIri":"%s","attributesJson":"{}"},
                    {"op":"upsert","iri":"%s","classIri":"%s","attributesJson":"{\\"%s\\":\\"A-01\\"}"},
                    {"op":"link","subjectIri":"%s","propertyIri":"%s","objectIri":"%s","subjectClassIri":"%s","objectClassIri":"%s"}
                  ]
                }
                """.formatted(
                        T0,
                        NS + "p4",
                        PALLET,
                        NS + "loc1",
                        STORAGE_LOCATION,
                        CODE,
                        NS + "p4",
                        STORED_AT,
                        NS + "loc1",
                        PALLET,
                        STORAGE_LOCATION);
        assertThat(client().post()
                        .uri(batchUri(PROJECT_ID))
                        .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(setup)
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(countLinks()).isEqualTo(1);

        String retire = """
                {
                  "at": "%s",
                  "ops": [ {"op":"retire","iri":"%s"} ]
                }
                """.formatted(T1, NS + "p4");
        assertThat(client().post()
                        .uri(batchUri(PROJECT_ID))
                        .header(API_KEY_HEADER, BOOTSTRAP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(retire)
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(jdbc().queryForObject(
                                "select count(*) from instances where iri = ? and valid_to is null",
                                Integer.class,
                                NS + "p4"))
                .isZero();
    }

    private RestClient client() {
        return RestClient.create();
    }

    private String batchUri(UUID projectId) {
        return "http://localhost:" + port + "/api/v1/projects/" + projectId + "/batch";
    }

    private static String upsertBody(Instant at, String iri, String classIri, String attributesJson) {
        return """
                {
                  "at": "%s",
                  "ops": [
                    {"op":"upsert","iri":"%s","classIri":"%s","attributesJson":%s}
                  ]
                }
                """.formatted(at, iri, classIri, quoteJson(attributesJson));
    }

    private static String quoteJson(String attributesJson) {
        return "\"" + attributesJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(instancesDataSource);
    }

    private long countInstances() {
        Integer count = jdbc().queryForObject("select count(*) from instances", Integer.class);
        return count == null ? 0 : count;
    }

    private long countLinks() {
        Integer count = jdbc().queryForObject("select count(*) from links", Integer.class);
        return count == null ? 0 : count;
    }

    private static void applyProjectionSchema(Connection connection) throws Exception {
        Path v5 = Path.of("..", "wp-app", "src", "main", "resources", "db", "migration", "V5__projection_schema.sql")
                .toAbsolutePath()
                .normalize();
        Path v9 = Path.of(
                        "..",
                        "wp-app",
                        "src",
                        "main",
                        "resources",
                        "db",
                        "migration",
                        "V9__projection_contract_version.sql")
                .toAbsolutePath()
                .normalize();
        try (Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(v5));
            statement.execute(Files.readString(v9));
        }
    }

    private static void seedProjection(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            Timestamp now = Timestamp.from(Instant.parse("2026-03-01T09:00:00Z"));
            statement.execute("""
                    insert into projection.project_state
                        (project_id, rev_no, rebuilt_at, updated_at, contract_version)
                    values ('%s', %d, '%s', '%s', %d)
                    """.formatted(PROJECT_ID, REV_NO, now, now, ProjectionContract.EXPECTED_VERSION));

            insertEntity(statement, HANDLING_UNIT, "CLASS", false);
            insertEntity(statement, PALLET, "CLASS", false);
            insertEntity(statement, LOCATION, "CLASS", false);
            insertEntity(statement, STORAGE_LOCATION, "CLASS", false);
            insertEntity(statement, STORED_AT, "OBJECT_PROPERTY", false);
            insertEntity(statement, CODE, "DATA_PROPERTY", false);

            statement.execute("""
                    insert into projection.hierarchy_edges (project_id, hierarchy, child_iri, parent_iri) values
                    ('%s', 'CLASS', '%s', '%s'),
                    ('%s', 'CLASS', '%s', '%s')
                    """.formatted(PROJECT_ID, STORAGE_LOCATION, LOCATION, PROJECT_ID, PALLET, HANDLING_UNIT));

            statement.execute("""
                    insert into projection.property_domains (project_id, property_iri, class_iri) values
                    ('%s', '%s', '%s'),
                    ('%s', '%s', '%s')
                    """.formatted(PROJECT_ID, STORED_AT, HANDLING_UNIT, PROJECT_ID, CODE, LOCATION));

            statement.execute("""
                    insert into projection.property_ranges (project_id, property_iri, range_iri, range_kind) values
                    ('%s', '%s', '%s', 'CLASS'),
                    ('%s', '%s', '%s', 'DATATYPE')
                    """.formatted(
                    PROJECT_ID, STORED_AT, LOCATION, PROJECT_ID, CODE, "http://www.w3.org/2001/XMLSchema#string"));
        }
    }

    private static void insertEntity(Statement statement, String iri, String type, boolean deprecated)
            throws Exception {
        statement.execute("""
                insert into projection.entities (project_id, iri, entity_type, deprecated)
                values ('%s', '%s', '%s', %s)
                """.formatted(PROJECT_ID, iri, type, deprecated));
    }

    private static String jdbcUrl(String database) {
        return postgres.getJdbcUrl().replaceFirst("/[^/]+$", "/" + database);
    }
}
