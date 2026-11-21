package wprebuild.instances;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TraceQueriesFollowTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T12:00:00Z");

    private static final String START = "http://ex/Start";
    private static final String P1 = "http://ex/p1";
    private static final String P2 = "http://ex/p2";
    private static final String P3 = "http://ex/p3";

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
    void chunks_handles_empty_exact_and_remainder() {
        assertThat(TraceQueries.chunks(List.of(), 3)).isEmpty();
        assertThat(TraceQueries.chunks(List.of("a", "b", "c", "d"), 2))
                .containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(TraceQueries.chunks(List.of("a", "b", "c", "d", "e"), 2))
                .containsExactly(List.of("a", "b"), List.of("c", "d"), List.of("e"));
    }

    @Test
    void follow_fanout_returns_all_complete_paths() {
        upsert(START);
        List<List<String>> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String mid = "http://ex/Mid" + i;
            upsert(mid);
            store.link(new LinkRecord(START, P1, mid), T0);
            for (int j = 0; j < 2; j++) {
                String end = "http://ex/End" + i + "_" + j;
                upsert(end);
                store.link(new LinkRecord(mid, P2, end), T0);
                expected.add(List.of(START, mid, end));
            }
        }

        List<PathStep> path = List.of(new PathStep(P1, Direction.FORWARD), new PathStep(P2, Direction.FORWARD));
        assertThat(queries.follow(START, path, T1)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void follow_diamond_keeps_separate_paths_through_shared_node() {
        String a = "http://ex/A";
        String b = "http://ex/B";
        String c = "http://ex/C";
        String d = "http://ex/D";
        String e = "http://ex/E";
        for (String iri : List.of(a, b, c, d, e)) {
            upsert(iri);
        }
        store.link(new LinkRecord(a, P1, b), T0);
        store.link(new LinkRecord(a, P1, c), T0);
        store.link(new LinkRecord(b, P2, d), T0);
        store.link(new LinkRecord(c, P2, d), T0);
        store.link(new LinkRecord(d, P3, e), T0);

        List<PathStep> path = List.of(
                new PathStep(P1, Direction.FORWARD),
                new PathStep(P2, Direction.FORWARD),
                new PathStep(P3, Direction.FORWARD));

        assertThat(queries.follow(a, path, T1)).containsExactlyInAnyOrder(List.of(a, b, d, e), List.of(a, c, d, e));
    }

    @Test
    void follow_mixed_backward_and_forward_three_hops() {
        String stacker = "http://ex/Stacker";
        String task = "http://ex/Task";
        String tray = "http://ex/Tray";
        String batch = "http://ex/Batch";
        String executedBy = "http://ex/executedBy";
        String moves = "http://ex/moves";
        String contains = "http://ex/contains";
        for (String iri : List.of(stacker, task, tray, batch)) {
            upsert(iri);
        }
        store.link(new LinkRecord(task, executedBy, stacker), T0);
        store.link(new LinkRecord(task, moves, tray), T0);
        store.link(new LinkRecord(batch, contains, tray), T0);

        List<PathStep> path = List.of(
                new PathStep(executedBy, Direction.BACKWARD),
                new PathStep(moves, Direction.FORWARD),
                new PathStep(contains, Direction.BACKWARD));

        assertThat(queries.follow(stacker, path, T1)).containsExactlyInAnyOrder(List.of(stacker, task, tray, batch));
    }

    @Test
    void follow_empty_when_middle_hop_has_no_match() {
        String a = "http://ex/A";
        String b = "http://ex/B";
        upsert(a);
        upsert(b);
        store.link(new LinkRecord(a, P1, b), T0);

        List<PathStep> path = List.of(new PathStep(P1, Direction.FORWARD), new PathStep(P2, Direction.FORWARD));
        assertThat(queries.follow(a, path, T1)).isEmpty();
    }

    @Test
    void follow_respects_as_of_valid_from_and_valid_to() {
        String a = "http://ex/A";
        String b = "http://ex/B";
        String c = "http://ex/C";
        upsert(a);
        upsert(b);
        upsert(c);
        store.link(new LinkRecord(a, P1, b), T1);
        store.link(new LinkRecord(b, P2, c), T1);
        store.unlink(new LinkRecord(b, P2, c), T2);

        List<PathStep> path = List.of(new PathStep(P1, Direction.FORWARD), new PathStep(P2, Direction.FORWARD));

        assertThat(queries.follow(a, path, T0)).isEmpty();
        assertThat(queries.follow(a, path, T1)).containsExactly(List.of(a, b, c));
        assertThat(queries.follow(a, path, T2)).isEmpty();
    }

    @Test
    void follow_fanout_uses_one_connection_and_one_statement_per_hop() {
        upsert(START);
        for (int i = 0; i < 50; i++) {
            String mid = "http://ex/Mid" + i;
            upsert(mid);
            store.link(new LinkRecord(START, P1, mid), T0);
            for (int j = 0; j < 2; j++) {
                String end = "http://ex/End" + i + "_" + j;
                upsert(end);
                store.link(new LinkRecord(mid, P2, end), T0);
            }
        }

        AtomicInteger connections = new AtomicInteger();
        AtomicInteger statements = new AtomicInteger();
        DataSource counting = countingDataSource(dataSource, connections, statements);
        TraceQueries counted = new TraceQueries(counting);

        List<PathStep> path = List.of(new PathStep(P1, Direction.FORWARD), new PathStep(P2, Direction.FORWARD));
        assertThat(counted.follow(START, path, T1)).hasSize(100);
        assertThat(connections.get()).isEqualTo(1);
        assertThat(statements.get()).isEqualTo(2);
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
                var statement = connection.createStatement()) {
            statement.execute("truncate links, instances");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void upsert(String iri) {
        store.upsert(new InstanceRecord(iri, "http://ex/Thing", "{}", "wcs", 1), T0);
    }

    private static DataSource countingDataSource(
            DataSource delegate, AtomicInteger connections, AtomicInteger statements) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && (args == null || args.length == 0)) {
                        connections.incrementAndGet();
                        Connection connection = (Connection) method.invoke(delegate, args);
                        return countingConnection(connection, statements);
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static Connection countingConnection(Connection delegate, AtomicInteger statements) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        statements.incrementAndGet();
                    }
                    Object result = method.invoke(delegate, args);
                    if (result instanceof PreparedStatement) {
                        return result;
                    }
                    return result;
                });
    }
}
