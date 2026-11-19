package wprebuild.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InstanceStoreTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant T3 = Instant.parse("2026-03-01T13:00:00Z");

    private static final String BATCH = "http://ex/Batch1";
    private static final String TRAY = "http://ex/Tray1";
    private static final String SLOT_A = "http://ex/SlotA";
    private static final String SLOT_B = "http://ex/SlotB";
    private static final String CONTAINS = "http://ex/contains";
    private static final String LOCATED_IN = "http://ex/locatedIn";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    private DataSource dataSource;
    private InstanceStore store;
    private TraceQueries queries;

    @BeforeEach
    void setUp() {
        dataSource = dataSource();
        InstanceSchema.migrate(dataSource);
        truncate();
        store = new InstanceStore(dataSource);
        queries = new TraceQueries(dataSource);
    }

    @Test
    void migrates_empty_database() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("""
                        select table_name from information_schema.tables
                        where table_schema = 'public'
                          and table_name in ('instances', 'links')
                        order by table_name
                        """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("instances");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("links");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void upsert_same_content_twice_keeps_single_version() throws Exception {
        InstanceRecord record = instance(BATCH, "http://ex/Batch", "{\"lot\":\"A\"}", "wcs", 1);
        store.upsert(record, T0);
        store.upsert(record, T1);

        assertThat(countRows("instances")).isEqualTo(1);
        assertSameInstance(store.find(BATCH, T1).orElseThrow(), record);
    }

    @Test
    void upsert_change_closes_old_version() {
        InstanceRecord v1 = instance(BATCH, "http://ex/Batch", "{\"lot\":\"A\"}", "wcs", 1);
        InstanceRecord v2 = instance(BATCH, "http://ex/Batch", "{\"lot\":\"B\"}", "wcs", 1);
        store.upsert(v1, T0);
        store.upsert(v2, T1);

        assertSameInstance(store.find(BATCH, T0).orElseThrow(), v1);
        assertSameInstance(store.find(BATCH, T1).orElseThrow(), v2);
        assertSameInstance(
                store.find(BATCH, Instant.parse("2026-03-01T10:30:00Z")).orElseThrow(), v1);
    }

    @Test
    void retire_hides_instance_and_links_from_current_but_keeps_history() {
        store.upsert(instance(BATCH, "http://ex/Batch", "{}", "wcs", 1), T0);
        store.upsert(instance(TRAY, "http://ex/Tray", "{}", "wcs", 1), T0);
        store.link(new LinkRecord(BATCH, CONTAINS, TRAY), T0);

        store.retire(BATCH, T1);

        assertThat(store.find(BATCH, T1)).isEmpty();
        assertThat(store.find(BATCH, T0)).isPresent();
        assertThat(queries.reachable(BATCH, Set.of(CONTAINS), Direction.FORWARD, 2, T1))
                .isEmpty();
        assertThat(queries.reachable(BATCH, Set.of(CONTAINS), Direction.FORWARD, 2, T0))
                .containsExactly(TRAY);
    }

    @Test
    void link_is_idempotent() throws Exception {
        store.upsert(instance(BATCH, "http://ex/Batch", "{}", "wcs", 1), T0);
        store.upsert(instance(TRAY, "http://ex/Tray", "{}", "wcs", 1), T0);
        LinkRecord link = new LinkRecord(BATCH, CONTAINS, TRAY);
        store.link(link, T0);
        store.link(link, T1);

        assertThat(countRows("links")).isEqualTo(1);
    }

    @Test
    void concurrent_link_of_same_triple_keeps_one_current_version() throws Exception {
        store.upsert(instance(BATCH, "http://ex/Batch", "{}", "wcs", 1), T0);
        store.upsert(instance(TRAY, "http://ex/Tray", "{}", "wcs", 1), T0);
        LinkRecord link = new LinkRecord(BATCH, CONTAINS, TRAY);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                store.link(link, T1);
                return null;
            });
            Future<?> b = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                store.link(link, T1);
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countCurrentLinks()).isEqualTo(1);
    }

    @Test
    void rejects_at_before_current_valid_from() {
        store.upsert(instance(BATCH, "http://ex/Batch", "{}", "wcs", 1), T1);
        store.upsert(instance(TRAY, "http://ex/Tray", "{}", "wcs", 1), T1);
        store.link(new LinkRecord(BATCH, CONTAINS, TRAY), T1);

        assertThatThrownBy(() -> store.upsert(instance(BATCH, "http://ex/Batch", "{\"x\":1}", "wcs", 1), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid_from");

        assertThatThrownBy(() -> store.link(new LinkRecord(BATCH, CONTAINS, TRAY), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid_from");
    }

    @Test
    void rejects_content_change_at_equal_to_valid_from() {
        store.upsert(instance(BATCH, "http://ex/Batch", "{}", "wcs", 1), T1);

        assertThatThrownBy(() -> store.upsert(instance(BATCH, "http://ex/Batch", "{\"x\":1}", "wcs", 1), T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be after");
    }

    @Test
    void reachable_terminates_on_cycles_without_duplicates() {
        String a = "http://ex/A";
        String b = "http://ex/B";
        String c = "http://ex/C";
        store.upsert(instance(a, "http://ex/Node", "{}", "wcs", 1), T0);
        store.upsert(instance(b, "http://ex/Node", "{}", "wcs", 1), T0);
        store.upsert(instance(c, "http://ex/Node", "{}", "wcs", 1), T0);
        store.link(new LinkRecord(a, CONTAINS, b), T0);
        store.link(new LinkRecord(b, CONTAINS, c), T0);
        store.link(new LinkRecord(c, CONTAINS, a), T0);

        assertThat(queries.reachable(a, Set.of(CONTAINS), Direction.FORWARD, 10, T0))
                .containsExactlyInAnyOrder(b, c);
    }

    @Test
    void follow_batch_tray_slot_respects_as_of_after_move() {
        store.upsert(instance(BATCH, "http://ex/Batch", "{}", "wcs", 1), T0);
        store.upsert(instance(TRAY, "http://ex/Tray", "{}", "wcs", 1), T0);
        store.upsert(instance(SLOT_A, "http://ex/Slot", "{}", "wcs", 1), T0);
        store.upsert(instance(SLOT_B, "http://ex/Slot", "{}", "wcs", 1), T0);
        store.link(new LinkRecord(BATCH, CONTAINS, TRAY), T0);
        store.link(new LinkRecord(TRAY, LOCATED_IN, SLOT_A), T0);

        // Move tray from SlotA to SlotB at T2.
        store.unlink(new LinkRecord(TRAY, LOCATED_IN, SLOT_A), T2);
        store.link(new LinkRecord(TRAY, LOCATED_IN, SLOT_B), T2);

        List<PathStep> path =
                List.of(new PathStep(CONTAINS, Direction.FORWARD), new PathStep(LOCATED_IN, Direction.FORWARD));

        assertThat(queries.follow(BATCH, path, T1)).containsExactly(List.of(BATCH, TRAY, SLOT_A));
        assertThat(queries.follow(BATCH, path, T3)).containsExactly(List.of(BATCH, TRAY, SLOT_B));
    }

    private DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private void truncate() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate links, instances");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long countRows(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countCurrentLinks() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from links where valid_to is null")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static InstanceRecord instance(
            String iri, String classIri, String attributesJson, String source, long ontologyRev) {
        return new InstanceRecord(iri, classIri, attributesJson, source, ontologyRev);
    }

    private static void assertSameInstance(InstanceRecord actual, InstanceRecord expected) {
        assertThat(actual.iri()).isEqualTo(expected.iri());
        assertThat(actual.classIri()).isEqualTo(expected.classIri());
        assertThat(actual.source()).isEqualTo(expected.source());
        assertThat(actual.ontologyRev()).isEqualTo(expected.ontologyRev());
        assertThat(actual.attributesJson().replaceAll("\\s+", ""))
                .isEqualTo(expected.attributesJson().replaceAll("\\s+", ""));
    }
}
