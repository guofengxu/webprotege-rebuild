package wprebuild.instances.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import wprebuild.instances.InstanceRecord;
import wprebuild.instances.InstanceSchema;
import wprebuild.instances.InstanceStore;

@Testcontainers
class RulesCacheTest {

    private static final String NS = "http://example.org/wp-test/warehouse#";
    private static final String LOCATION = NS + "Location";
    private static final String CODE = NS + "code";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";
    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
    private static final long REV_NO = 10L;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    private static DataSource projectionDs;
    private static DataSource instanceDs;

    private InstanceStore store;
    private AtomicInteger fullLoadCount;
    private CachedRuleSource cached;

    @BeforeAll
    static void createDatabases() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database wp_projection_cache");
            statement.execute("create database wp_instances_cache");
        }
        projectionDs = dataSource("wp_projection_cache");
        instanceDs = dataSource("wp_instances_cache");
        applyProjectionSchema(projectionDs);
        InstanceSchema.migrate(instanceDs);
        seedProjection(projectionDs, PROJECT_A, REV_NO);
        seedProjection(projectionDs, PROJECT_B, REV_NO);
    }

    @BeforeEach
    void setUp() throws Exception {
        truncateInstances();
        resetProjectState(PROJECT_A, REV_NO, ProjectionContract.EXPECTED_VERSION);
        resetProjectState(PROJECT_B, REV_NO, ProjectionContract.EXPECTED_VERSION);
        store = new InstanceStore(instanceDs);
        fullLoadCount = new AtomicInteger();
        ProjectionRuleSource underlying = new ProjectionRuleSource(projectionDs);
        Function<UUID, ValidationRules> countingLoader = projectId -> {
            fullLoadCount.incrementAndGet();
            return underlying.load(projectId);
        };
        cached = new CachedRuleSource(projectionDs, countingLoader);
    }

    @Test
    void first_load_hits_projection_same_rev_reuses_cache() {
        ValidationRules first = cached.rulesFor(PROJECT_A);
        assertThat(first.ontologyRev()).isEqualTo(REV_NO);
        assertThat(first.classExists(LOCATION)).isTrue();
        assertThat(fullLoadCount.get()).isEqualTo(1);

        ValidationRules second = cached.rulesFor(PROJECT_A);
        assertThat(second).isSameAs(first);
        assertThat(fullLoadCount.get()).isEqualTo(1);
    }

    @Test
    void rev_advance_reloads_rules() throws Exception {
        ValidationRules before = cached.rulesFor(PROJECT_A);
        assertThat(before.ontologyRev()).isEqualTo(REV_NO);
        assertThat(fullLoadCount.get()).isEqualTo(1);

        long advanced = REV_NO + 5;
        resetProjectState(PROJECT_A, advanced, ProjectionContract.EXPECTED_VERSION);

        ValidationRules after = cached.rulesFor(PROJECT_A);
        assertThat(after.ontologyRev()).isEqualTo(advanced);
        assertThat(after).isNotSameAs(before);
        assertThat(fullLoadCount.get()).isEqualTo(2);

        // Other project's cache is untouched.
        ValidationRules other = cached.rulesFor(PROJECT_B);
        assertThat(other.ontologyRev()).isEqualTo(REV_NO);
        assertThat(fullLoadCount.get()).isEqualTo(3);
        ValidationRules otherAgain = cached.rulesFor(PROJECT_B);
        assertThat(otherAgain).isSameAs(other);
        assertThat(fullLoadCount.get()).isEqualTo(3);
    }

    @Test
    void contract_version_mismatch_fails_and_write_does_not_land() throws Exception {
        cached.rulesFor(PROJECT_A);
        assertThat(fullLoadCount.get()).isEqualTo(1);

        resetProjectState(PROJECT_A, REV_NO, ProjectionContract.EXPECTED_VERSION + 1);

        assertThatThrownBy(() -> cached.rulesFor(PROJECT_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contract_version")
                .hasMessageContaining(String.valueOf(ProjectionContract.EXPECTED_VERSION))
                .hasMessageContaining(String.valueOf(ProjectionContract.EXPECTED_VERSION + 1));
        // Must not fall back to the stale cache entry.
        assertThat(fullLoadCount.get()).isEqualTo(1);

        assertThatThrownBy(() -> {
                    ValidationRules rules = cached.rulesFor(PROJECT_A);
                    new ValidatingInstanceWriter(store, rules)
                            .upsert(new InstanceRecord(NS + "loc1", LOCATION, "{}", "wcs", 0L), T0);
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contract_version");
        assertThat(countRows("instances")).isZero();
    }

    @Test
    void missing_project_state_matches_projection_rule_source() {
        UUID missing = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String expectedMessage = "project " + missing + " has no ontology projection; enable projection first";

        assertThatThrownBy(() -> new ProjectionRuleSource(projectionDs).load(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);

        assertThatThrownBy(() -> cached.rulesFor(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);
    }

    private static DataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl(database));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static String jdbcUrl(String database) {
        String base = postgres.getJdbcUrl();
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + database;
    }

    private static void applyProjectionSchema(DataSource dataSource) throws Exception {
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
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(v5));
            statement.execute(Files.readString(v9));
        }
    }

    private static void seedProjection(DataSource dataSource, UUID projectId, long revNo) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            Timestamp now = Timestamp.from(Instant.parse("2026-03-01T09:00:00Z"));
            statement.execute("""
                    insert into projection.project_state
                        (project_id, rev_no, rebuilt_at, updated_at, contract_version)
                    values ('%s', %d, '%s', '%s', %d)
                    """.formatted(projectId, revNo, now, now, ProjectionContract.EXPECTED_VERSION));

            statement.execute("""
                    insert into projection.entities (project_id, iri, entity_type, deprecated) values
                    ('%s', '%s', 'CLASS', false),
                    ('%s', '%s', 'DATA_PROPERTY', false)
                    """.formatted(projectId, LOCATION, projectId, CODE));

            statement.execute("""
                    insert into projection.property_domains (project_id, property_iri, class_iri)
                    values ('%s', '%s', '%s')
                    """.formatted(projectId, CODE, LOCATION));

            statement.execute("""
                    insert into projection.property_ranges
                        (project_id, property_iri, range_iri, range_kind)
                    values ('%s', '%s', '%s', 'DATATYPE')
                    """.formatted(projectId, CODE, XSD_STRING));
        }
    }

    private static void resetProjectState(UUID projectId, long revNo, int contractVersion) throws Exception {
        try (Connection connection = projectionDs.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    update projection.project_state
                    set rev_no = %d, contract_version = %d
                    where project_id = '%s'
                    """.formatted(revNo, contractVersion, projectId));
        }
    }

    private void truncateInstances() throws Exception {
        try (Connection connection = instanceDs.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate links, instances");
        }
    }

    private long countRows(String table) throws Exception {
        try (Connection connection = instanceDs.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
