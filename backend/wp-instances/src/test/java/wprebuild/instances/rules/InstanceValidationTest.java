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
import java.util.List;
import java.util.UUID;
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
import wprebuild.instances.LinkRecord;

@Testcontainers
class InstanceValidationTest {

    private static final String NS = "http://example.org/wp-test/warehouse#";
    private static final String HANDLING_UNIT = NS + "HandlingUnit";
    private static final String PALLET = NS + "Pallet";
    private static final String LOCATION = NS + "Location";
    private static final String STORAGE_LOCATION = NS + "StorageLocation";
    private static final String WAREHOUSE = NS + "Warehouse";
    private static final String ZONE = NS + "Zone";
    private static final String DEPRECATED_CLASS = NS + "LegacySlot";
    private static final String STORED_AT = NS + "storedAt";
    private static final String CODE = NS + "code";
    private static final String SLOT_COUNT = NS + "slotCount";
    private static final String SHARED = NS + "sharedAcross";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";
    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final long REV_NO = 42L;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    private static DataSource projectionDs;
    private static DataSource instanceDs;

    private InstanceStore store;
    private ValidatingInstanceWriter writer;
    private ValidationRules rules;

    @BeforeAll
    static void createDatabases() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database wp_projection");
            statement.execute("create database wp_instances");
        }
        projectionDs = dataSource("wp_projection");
        instanceDs = dataSource("wp_instances");
        applyProjectionSchema(projectionDs);
        InstanceSchema.migrate(instanceDs);
        seedProjection(projectionDs);
    }

    @BeforeEach
    void setUp() throws Exception {
        truncateInstances();
        store = new InstanceStore(instanceDs);
        rules = new ProjectionRuleSource(projectionDs).load(PROJECT_ID);
        writer = new ValidatingInstanceWriter(store, rules);
    }

    @Test
    void load_throws_when_project_has_no_projection() {
        UUID missing = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertThatThrownBy(() -> new ProjectionRuleSource(projectionDs).load(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projection");
    }

    @Test
    void subclass_instance_satisfies_parent_domain() {
        // Pallet ⊑ HandlingUnit; storedAt domain is HandlingUnit.
        LinkRecord link = new LinkRecord(NS + "p1", STORED_AT, NS + "loc1");
        assertThat(new InstanceValidator().validateLink(link, PALLET, STORAGE_LOCATION, rules))
                .isEmpty();

        writer.upsert(instance(NS + "p1", PALLET, "{}"), T0);
        writer.upsert(instance(NS + "loc1", STORAGE_LOCATION, "{\"" + CODE + "\": \"A-01\"}"), T0);
        writer.link(link, PALLET, STORAGE_LOCATION, T0);
        assertThat(store.find(NS + "p1", T0))
                .isPresent()
                .get()
                .extracting(InstanceRecord::ontologyRev)
                .isEqualTo(REV_NO);
    }

    @Test
    void two_domains_require_intersection() {
        InstanceRecord onlyWarehouse = instance(NS + "w1", WAREHOUSE, "{\"" + SHARED + "\":1}");
        List<Violation> violations = new InstanceValidator().validate(onlyWarehouse, rules);
        assertThat(violations).anySatisfy(v -> {
            assertThat(v.code()).isEqualTo("DOMAIN_MISMATCH");
            assertThat(v.propertyIri()).isEqualTo(SHARED);
        });
    }

    @Test
    void range_mismatch_on_link() {
        LinkRecord link = new LinkRecord(NS + "p1", STORED_AT, NS + "w1");
        List<Violation> violations = new InstanceValidator().validateLink(link, PALLET, WAREHOUSE, rules);
        assertThat(violations).anySatisfy(v -> assertThat(v.code()).isEqualTo("RANGE_MISMATCH"));
    }

    @Test
    void unknown_property_and_class_and_deprecated() {
        InstanceRecord unknownClass = instance(NS + "x1", NS + "NoSuchClass", "{}");
        assertThat(new InstanceValidator().validate(unknownClass, rules))
                .anySatisfy(v -> assertThat(v.code()).isEqualTo("UNKNOWN_CLASS"));

        InstanceRecord unknownProp = instance(NS + "loc1", LOCATION, "{\"" + NS + "noSuch\": \"a\"}");
        assertThat(new InstanceValidator().validate(unknownProp, rules))
                .anySatisfy(v -> assertThat(v.code()).isEqualTo("UNKNOWN_PROPERTY"));

        InstanceRecord deprecated = instance(NS + "legacy1", DEPRECATED_CLASS, "{}");
        assertThat(new InstanceValidator().validate(deprecated, rules))
                .anySatisfy(v -> assertThat(v.code()).isEqualTo("DEPRECATED_CLASS"));
    }

    @Test
    void datatype_mismatch() {
        InstanceRecord bad = instance(NS + "loc2", LOCATION, "{\"" + CODE + "\": 12}");
        assertThat(new InstanceValidator().validate(bad, rules)).anySatisfy(v -> {
            assertThat(v.code()).isEqualTo("DATATYPE_MISMATCH");
            assertThat(v.propertyIri()).isEqualTo(CODE);
        });

        InstanceRecord ok =
                instance(NS + "loc3", STORAGE_LOCATION, "{\"" + CODE + "\": \"A-01\", \"" + SLOT_COUNT + "\": 3}");
        assertThat(new InstanceValidator().validate(ok, rules)).isEmpty();
    }

    @Test
    void failed_validation_writes_nothing() throws Exception {
        InstanceRecord bad = instance(NS + "bad", NS + "NoSuchClass", "{}");
        assertThatThrownBy(() -> writer.upsert(bad, T0))
                .isInstanceOf(InstanceValidationException.class)
                .satisfies(ex -> {
                    InstanceValidationException ive = (InstanceValidationException) ex;
                    assertThat(ive.violations()).isNotEmpty();
                });
        assertThat(countRows("instances")).isZero();

        LinkRecord badLink = new LinkRecord(NS + "p1", NS + "noSuchProp", NS + "loc1");
        assertThatThrownBy(() -> writer.link(badLink, PALLET, LOCATION, T0))
                .isInstanceOf(InstanceValidationException.class);
        assertThat(countRows("links")).isZero();
    }

    @Test
    void successful_link_and_instance_use_projection_rev() throws Exception {
        writer.upsert(instance(NS + "p2", PALLET, "{}"), T0);
        writer.upsert(instance(NS + "loc4", STORAGE_LOCATION, "{\"" + CODE + "\": \"B-02\"}"), T0);
        writer.link(new LinkRecord(NS + "p2", STORED_AT, NS + "loc4"), PALLET, STORAGE_LOCATION, T0);

        assertThat(store.find(NS + "p2", T0).orElseThrow().ontologyRev()).isEqualTo(REV_NO);
        assertThat(countRows("links")).isEqualTo(1);
        assertThat(rules.ontologyRev()).isEqualTo(REV_NO);
        assertThat(rules.classExists(PALLET)).isTrue();
        assertThat(rules.ancestorClosure(STORAGE_LOCATION)).contains(LOCATION);
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
        Path schema = Path.of(
                        "..", "wp-app", "src", "main", "resources", "db", "migration", "V5__projection_schema.sql")
                .toAbsolutePath()
                .normalize();
        String sql = Files.readString(schema);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void seedProjection(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            Timestamp now = Timestamp.from(Instant.parse("2026-03-01T09:00:00Z"));
            statement.execute("""
                    insert into projection.project_state (project_id, rev_no, rebuilt_at, updated_at)
                    values ('%s', %d, '%s', '%s')
                    """.formatted(PROJECT_ID, REV_NO, now, now));

            insertEntity(statement, HANDLING_UNIT, "CLASS", false);
            insertEntity(statement, PALLET, "CLASS", false);
            insertEntity(statement, LOCATION, "CLASS", false);
            insertEntity(statement, STORAGE_LOCATION, "CLASS", false);
            insertEntity(statement, WAREHOUSE, "CLASS", false);
            insertEntity(statement, ZONE, "CLASS", false);
            insertEntity(statement, DEPRECATED_CLASS, "CLASS", true);
            insertEntity(statement, STORED_AT, "OBJECT_PROPERTY", false);
            insertEntity(statement, CODE, "DATA_PROPERTY", false);
            insertEntity(statement, SLOT_COUNT, "DATA_PROPERTY", false);
            insertEntity(statement, SHARED, "DATA_PROPERTY", false);

            // Two-level subclass: StorageLocation ⊑ Location; Pallet ⊑ HandlingUnit.
            statement.execute("""
                    insert into projection.hierarchy_edges (project_id, hierarchy, child_iri, parent_iri) values
                    ('%s', 'CLASS', '%s', '%s'),
                    ('%s', 'CLASS', '%s', '%s')
                    """.formatted(PROJECT_ID, STORAGE_LOCATION, LOCATION, PROJECT_ID, PALLET, HANDLING_UNIT));

            statement.execute("""
                    insert into projection.property_domains (project_id, property_iri, class_iri) values
                    ('%s', '%s', '%s'),
                    ('%s', '%s', '%s'),
                    ('%s', '%s', '%s'),
                    ('%s', '%s', '%s'),
                    ('%s', '%s', '%s')
                    """.formatted(
                            PROJECT_ID,
                            STORED_AT,
                            HANDLING_UNIT,
                            PROJECT_ID,
                            CODE,
                            LOCATION,
                            PROJECT_ID,
                            SLOT_COUNT,
                            LOCATION,
                            PROJECT_ID,
                            SHARED,
                            WAREHOUSE,
                            PROJECT_ID,
                            SHARED,
                            ZONE));

            statement.execute("""
                    insert into projection.property_ranges (project_id, property_iri, range_iri, range_kind) values
                    ('%s', '%s', '%s', 'CLASS'),
                    ('%s', '%s', '%s', 'DATATYPE'),
                    ('%s', '%s', '%s', 'DATATYPE'),
                    ('%s', '%s', '%s', 'DATATYPE')
                    """.formatted(
                            PROJECT_ID,
                            STORED_AT,
                            LOCATION,
                            PROJECT_ID,
                            CODE,
                            XSD_STRING,
                            PROJECT_ID,
                            SLOT_COUNT,
                            XSD_INTEGER,
                            PROJECT_ID,
                            SHARED,
                            XSD_INTEGER));
        }
    }

    private static void insertEntity(Statement statement, String iri, String type, boolean deprecated)
            throws Exception {
        statement.execute("""
                insert into projection.entities (project_id, iri, entity_type, deprecated)
                values ('%s', '%s', '%s', %s)
                """.formatted(PROJECT_ID, iri, type, deprecated));
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

    private static InstanceRecord instance(String iri, String classIri, String attributesJson) {
        return new InstanceRecord(iri, classIri, attributesJson, "wcs", 0L);
    }
}
