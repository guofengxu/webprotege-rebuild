package wprebuild.instances.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import wprebuild.instances.Direction;
import wprebuild.instances.InstanceRecord;
import wprebuild.instances.InstanceSchema;
import wprebuild.instances.InstanceStore;
import wprebuild.instances.LinkRecord;
import wprebuild.instances.PathStep;
import wprebuild.instances.TraceQueries;

/**
 * Instance-store scale benchmark (P6-32). Skipped unless {@code -Dbench=true}. Smoke mode:
 * {@code -Dbench.smoke=true}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "bench", matches = "true")
class InstanceBenchmark {

    private static final Logger log = LoggerFactory.getLogger(InstanceBenchmark.class);

    private static final int WARMUP = 20;
    private static final int SAMPLES = 100;
    private static final int SMOKE_WARMUP = 5;
    private static final int SMOKE_SAMPLES = 20;

    private static final long Q123_P95_LIMIT_NANOS = 200L * 1_000_000L;
    private static final long Q45_P95_LIMIT_NANOS = 1_000L * 1_000_000L;
    private static final double W1_MIN_THROUGHPUT = 200d;
    private static final long W1_P95_LIMIT_NANOS = 50L * 1_000_000L;

    /**
     * Deep trace: batch → trays → historical slots → excursion events overlapping the stay →
     * sensors.
     */
    static final String Q4_DEEP_TRACE_SQL = """
            with trays as (
                select l.object_iri as tray_iri
                from links l
                where l.subject_iri = ?
                  and l.property_iri = ?
                  and l.valid_from <= ?
                  and (l.valid_to is null or l.valid_to > ?)
            ),
            placements as (
                select s.tray_iri,
                       l.object_iri as slot_iri,
                       l.valid_from as stay_from,
                       l.valid_to as stay_to
                from trays s
                join links l
                  on l.subject_iri = s.tray_iri
                 and l.property_iri = ?
            ),
            monitored as (
                select p.tray_iri,
                       p.slot_iri,
                       p.stay_from,
                       p.stay_to,
                       m.subject_iri as sensor_iri
                from placements p
                join links m
                  on m.object_iri = p.slot_iri
                 and m.property_iri = ?
                 and m.valid_from <= p.stay_from
                 and (m.valid_to is null or m.valid_to > p.stay_from)
            )
            select distinct e.iri as event_iri,
                   m.sensor_iri,
                   m.slot_iri,
                   m.tray_iri
            from monitored m
            join links o
              on o.object_iri = m.sensor_iri
             and o.property_iri = ?
            join instances e
              on e.iri = o.subject_iri
             and e.class_iri = ?
             and (e.attributes->>'excursion') = 'true'
             and e.valid_from >= m.stay_from
             and (m.stay_to is null or e.valid_from < m.stay_to)
            """;

    /** Reverse trace: excursion event → slot (via sensor) → trays present then → batches. */
    static final String Q5_REVERSE_TRACE_SQL = """
            with event as (
                select i.iri as event_iri,
                       i.valid_from as event_at,
                       o.object_iri as sensor_iri
                from instances i
                join links o
                  on o.subject_iri = i.iri
                 and o.property_iri = ?
                where i.iri = ?
            ),
            slot as (
                select e.event_iri,
                       e.event_at,
                       e.sensor_iri,
                       m.object_iri as slot_iri
                from event e
                join links m
                  on m.subject_iri = e.sensor_iri
                 and m.property_iri = ?
                 and m.valid_from <= e.event_at
                 and (m.valid_to is null or m.valid_to > e.event_at)
            ),
            trays as (
                select s.event_iri,
                       s.event_at,
                       s.sensor_iri,
                       s.slot_iri,
                       st.subject_iri as tray_iri
                from slot s
                join links st
                  on st.object_iri = s.slot_iri
                 and st.property_iri = ?
                 and st.valid_from <= s.event_at
                 and (st.valid_to is null or st.valid_to > s.event_at)
            )
            select c.subject_iri as batch_iri,
                   tr.tray_iri,
                   tr.slot_iri,
                   tr.sensor_iri
            from trays tr
            join links c
              on c.object_iri = tr.tray_iri
             and c.property_iri = ?
             and c.valid_from <= tr.event_at
             and (c.valid_to is null or c.valid_to > tr.event_at)
            """;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    private DataSource dataSource;
    private InstanceStore store;
    private TraceQueries queries;

    @BeforeEach
    void setUp() {
        dataSource = dataSource();
        InstanceSchema.migrate(dataSource);
        store = new InstanceStore(dataSource);
        queries = new TraceQueries(dataSource);
    }

    @Test
    // Full Q3 is N+1 via TraceQueries.follow (~80s/sample × 120 × 2 rounds); allow headroom for W1.
    @Timeout(value = 12, unit = TimeUnit.HOURS)
    void writes_the_instances_poc_report() throws Exception {
        boolean smoke = Boolean.getBoolean("bench.smoke");
        int warmup = smoke ? SMOKE_WARMUP : WARMUP;
        int samples = smoke ? SMOKE_SAMPLES : SAMPLES;
        WarehouseCorpus.Scale scale = smoke ? WarehouseCorpus.smoke() : WarehouseCorpus.full();

        String postgresVersion = queryScalar("show server_version");
        Map<String, String> pgSettings = readPgSettings();

        log.info("loading corpus smoke={}", smoke);
        long loadStart = System.nanoTime();
        WarehouseCorpus.Loaded loaded = WarehouseCorpus.load(dataSource, scale);
        long loadNanos = System.nanoTime() - loadStart;

        analyze();
        TableSizes sizes = tableSizes();

        // Two query rounds before W1 so row counts stay comparable (W1 mutates trays/links).
        QueryBundle firstQueries = measureQueries(loaded, warmup, samples, "1");
        QueryBundle secondQueries = measureQueries(loaded, warmup, samples, "2");

        assertThat(firstQueries.q1().rowCount()).isEqualTo(secondQueries.q1().rowCount());
        assertThat(firstQueries.q2().rowCount()).isEqualTo(secondQueries.q2().rowCount());
        assertThat(firstQueries.q3().rowCount()).isEqualTo(secondQueries.q3().rowCount());
        assertThat(firstQueries.q4().rowCount()).isEqualTo(secondQueries.q4().rowCount());
        assertThat(firstQueries.q5().rowCount()).isEqualTo(secondQueries.q5().rowCount());

        String explainQ1 = explainQ1(loaded);
        String explainQ4 = explainSql(Q4_DEEP_TRACE_SQL, q4Params(loaded));
        String explainQ5 = explainSql(Q5_REVERSE_TRACE_SQL, q5Params(loaded));
        WriteRun w1 = measureW1(loaded, smoke);
        BenchRun first = new BenchRun(
                firstQueries.q1(),
                firstQueries.q2(),
                firstQueries.q3(),
                firstQueries.q4(),
                firstQueries.q5(),
                w1,
                explainQ1,
                explainQ4,
                explainQ5);
        BenchRun second = new BenchRun(
                secondQueries.q1(),
                secondQueries.q2(),
                secondQueries.q3(),
                secondQueries.q4(),
                secondQueries.q5(),
                null,
                "",
                "",
                "");

        Path report = writeReport(loaded, first, second, loadNanos, sizes, postgresVersion, pgSettings, smoke);
        assertThat(report).isRegularFile();
        String text = Files.readString(report);
        assertThat(text).contains("门禁结论", "Q4_DEEP_TRACE_SQL", "Q5_REVERSE_TRACE_SQL");

        if (smoke) {
            log.info("smoke report {} (gates not asserted)", report);
            return;
        }

        List<String> failures = gateFailures(first);
        assertThat(failures)
                .withFailMessage("instance PoC gates missed after report %s: %s", report, failures)
                .isEmpty();
        log.info("report {} gates pass", report);
    }

    private QueryBundle measureQueries(WarehouseCorpus.Loaded loaded, int warmup, int samples, String round)
            throws Exception {
        QueryRun q1 = sampleQuery("Q1-" + round, warmup, samples, () -> runQ1(loaded));
        QueryRun q2 = sampleQuery("Q2-" + round, warmup, samples, () -> runQ2(loaded));
        QueryRun q3 = sampleQuery("Q3-" + round, warmup, samples, () -> runQ3(loaded));
        QueryRun q4 = sampleQuery("Q4-" + round, warmup, samples, () -> runQ4(loaded));
        QueryRun q5 = sampleQuery("Q5-" + round, warmup, samples, () -> runQ5(loaded));
        return new QueryBundle(q1, q2, q3, q4, q5);
    }

    private int runQ1(WarehouseCorpus.Loaded loaded) {
        Instant asOf = loaded.asOf().minusSeconds(1);
        return queries.reachable(
                        loaded.samples().slotIri(), Set.of(WarehouseCorpus.PROP_STORED_AT), Direction.BACKWARD, 1, asOf)
                .size();
    }

    private int runQ2(WarehouseCorpus.Loaded loaded) {
        Instant asOf = loaded.asOf().minusSeconds(1);
        List<PathStep> path = List.of(
                new PathStep(WarehouseCorpus.PROP_CONTAINS, Direction.FORWARD),
                new PathStep(WarehouseCorpus.PROP_STORED_AT, Direction.FORWARD));
        return queries.follow(loaded.samples().batchIri(), path, asOf).size();
    }

    private int runQ3(WarehouseCorpus.Loaded loaded) {
        Instant asOf = WarehouseCorpus.DAY0
                .plus(loaded.samples().taskDay(), ChronoUnit.DAYS)
                .plus(12, ChronoUnit.HOURS);
        List<PathStep> path = List.of(
                new PathStep(WarehouseCorpus.PROP_EXECUTED_BY, Direction.BACKWARD),
                new PathStep(WarehouseCorpus.PROP_MOVES, Direction.FORWARD),
                new PathStep(WarehouseCorpus.PROP_CONTAINS, Direction.BACKWARD));
        return queries.follow(loaded.samples().stackerIri(), path, asOf).size();
    }

    private int runQ4(WarehouseCorpus.Loaded loaded) throws SQLException {
        return countSql(Q4_DEEP_TRACE_SQL, q4Params(loaded));
    }

    private int runQ5(WarehouseCorpus.Loaded loaded) throws SQLException {
        return countSql(Q5_REVERSE_TRACE_SQL, q5Params(loaded));
    }

    private Object[] q4Params(WarehouseCorpus.Loaded loaded) {
        Instant asOf = loaded.asOf().minusSeconds(1);
        Timestamp ts = Timestamp.from(asOf);
        return new Object[] {
            loaded.samples().deepBatchIri(),
            WarehouseCorpus.PROP_CONTAINS,
            ts,
            ts,
            WarehouseCorpus.PROP_STORED_AT,
            WarehouseCorpus.PROP_MONITORS,
            WarehouseCorpus.PROP_OBSERVED_BY,
            WarehouseCorpus.CLASS_ENV_EVENT
        };
    }

    private Object[] q5Params(WarehouseCorpus.Loaded loaded) {
        return new Object[] {
            WarehouseCorpus.PROP_OBSERVED_BY,
            loaded.samples().excursionEventIri(),
            WarehouseCorpus.PROP_MONITORS,
            WarehouseCorpus.PROP_STORED_AT,
            WarehouseCorpus.PROP_CONTAINS
        };
    }

    private WriteRun measureW1(WarehouseCorpus.Loaded loaded, boolean smoke) throws Exception {
        int threads = 8;
        long durationMs = smoke ? 5_000L : 60_000L;
        AtomicBoolean running = new AtomicBoolean(true);
        LongAdder ops = new LongAdder();
        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // Monotonic synthetic clock with large gaps so concurrent workers never rewrite history.
        AtomicLong clock = new AtomicLong(0);
        Instant clockBase = loaded.asOf().plusSeconds(60);
        Random seed = new Random(WarehouseCorpus.SEED + 99);
        // Partition trays by worker so writers do not contend on the same IRI (avoids lock pile-ups
        // that previously hung W1 past the suite timeout after the long Q3 rounds).
        int[] trayCursor = new int[threads];

        Instant wallStart = Instant.now();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            long threadSeed = seed.nextLong();
            pool.submit(() -> {
                Random random = new Random(threadSeed);
                ready.countDown();
                try {
                    ready.await(30, TimeUnit.SECONDS);
                    while (running.get()) {
                        long start = System.nanoTime();
                        Instant at = clockBase.plusMillis(clock.incrementAndGet() * 10L);
                        try {
                            int tray = nextOwnedTray(loaded.scale().trays(), threads, threadId, trayCursor);
                            if (random.nextDouble() < 0.7d) {
                                String status = random.nextBoolean() ? "moving" : "stored";
                                store.upsert(
                                        new InstanceRecord(
                                                WarehouseCorpus.trayIri(tray),
                                                WarehouseCorpus.CLASS_TRAY,
                                                "{\"status\":\"" + status + "\",\"w\":" + threadId + "}",
                                                "wcs",
                                                1L),
                                        at);
                            } else {
                                String trayIri = WarehouseCorpus.trayIri(tray);
                                String fromSlot = currentStoredAt(trayIri);
                                if (fromSlot != null) {
                                    store.unlink(new LinkRecord(trayIri, WarehouseCorpus.PROP_STORED_AT, fromSlot), at);
                                }
                                int toSlot = Math.floorMod(
                                        (fromSlot == null ? tray : fromSlot.hashCode()) + 1 + random.nextInt(50),
                                        loaded.scale().slots());
                                Instant linkAt = clockBase.plusMillis(clock.incrementAndGet() * 10L);
                                store.link(
                                        new LinkRecord(
                                                trayIri,
                                                WarehouseCorpus.PROP_STORED_AT,
                                                WarehouseCorpus.slotIri(toSlot)),
                                        linkAt);
                            }
                            latencies.add(System.nanoTime() - start);
                            ops.increment();
                        } catch (IllegalArgumentException | IllegalStateException | SQLException ex) {
                            // Concurrent version conflicts are expected under load; skip the sample.
                            log.debug("W1 conflict on worker {}: {}", threadId, ex.toString());
                        }
                    }
                } catch (Exception ex) {
                    log.warn("W1 worker {} stopped: {}", threadId, ex.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(durationMs);
        running.set(false);
        boolean finished = done.await(180, TimeUnit.SECONDS);
        pool.shutdownNow();
        if (!finished) {
            log.warn("W1 workers did not finish within 180s after stop; reporting partial samples");
        }
        Instant wallEnd = Instant.now();

        long[] nanos = latencies.stream().mapToLong(Long::longValue).toArray();
        double seconds = Math.max(0.001d, (wallEnd.toEpochMilli() - wallStart.toEpochMilli()) / 1000d);
        double throughput = ops.sum() / seconds;
        return new WriteRun(
                ops.sum(),
                throughput,
                percentile(nanos, 0.50d),
                percentile(nanos, 0.95d),
                percentile(nanos, 0.99d),
                maxOf(nanos),
                durationMs);
    }

    /** Round-robin tray index owned by {@code threadId} (tray % threads == threadId). */
    private static int nextOwnedTray(int trayCount, int threads, int threadId, int[] cursors) {
        int cursor = cursors[threadId];
        for (int attempt = 0; attempt < trayCount; attempt++) {
            int tray = Math.floorMod(cursor + attempt, trayCount);
            if (tray % threads == threadId) {
                cursors[threadId] = tray + 1;
                return tray;
            }
        }
        return threadId % trayCount;
    }

    private String currentStoredAt(String trayIri) throws SQLException {
        String sql = """
                select object_iri
                from links
                where subject_iri = ?
                  and property_iri = ?
                  and valid_to is null
                limit 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, trayIri);
            ps.setString(2, WarehouseCorpus.PROP_STORED_AT);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private QueryRun sampleQuery(String label, int warmup, int samples, QueryIntSupplier query) throws Exception {
        for (int i = 0; i < warmup; i++) {
            query.getAsInt();
        }
        BackgroundSnapshot before = captureBackground();
        long[] nanos = new long[samples];
        int rows = 0;
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            rows = query.getAsInt();
            nanos[i] = System.nanoTime() - start;
        }
        BackgroundSnapshot after = captureBackground();
        log.info("{} rows={} p95={}ms", label, rows, millis(percentile(nanos, 0.95d)));
        return new QueryRun(
                nanos,
                percentile(nanos, 0.50d),
                percentile(nanos, 0.95d),
                percentile(nanos, 0.99d),
                maxOf(nanos),
                rows,
                BackgroundDelta.of(before, after));
    }

    private void analyze() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("analyze instances");
            statement.execute("analyze links");
        }
    }

    private String explainQ1(WarehouseCorpus.Loaded loaded) throws SQLException {
        String sql = """
                explain (analyze, buffers)
                select subject_iri
                from links
                where object_iri = ?
                  and property_iri = ?
                  and valid_from <= ?
                  and (valid_to is null or valid_to > ?)
                """;
        Instant asOf = loaded.asOf().minusSeconds(1);
        Timestamp ts = Timestamp.from(asOf);
        return explainSql(sql, new Object[] {loaded.samples().slotIri(), WarehouseCorpus.PROP_STORED_AT, ts, ts});
    }

    private String explainSql(String sql, Object[] params) throws SQLException {
        String explain = sql.trim().regionMatches(true, 0, "explain", 0, 7) ? sql : "explain (analyze, buffers) " + sql;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(explain)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder out = new StringBuilder();
                while (rs.next()) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(rs.getString(1));
                }
                return out.toString();
            }
        }
    }

    private int countSql(String sql, Object[] params) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                }
                return rows;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof Timestamp timestamp) {
                ps.setTimestamp(i + 1, timestamp);
            } else {
                ps.setString(i + 1, (String) value);
            }
        }
    }

    private TableSizes tableSizes() throws SQLException {
        long instances = count("instances");
        long links = count("links");
        String instanceBytes = queryScalar("select pg_size_pretty(pg_total_relation_size('instances'))");
        String linkBytes = queryScalar("select pg_size_pretty(pg_total_relation_size('links'))");
        return new TableSizes(instances, links, instanceBytes, linkBytes);
    }

    private long count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String queryScalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private Map<String, String> readPgSettings() throws SQLException {
        List<String> names = List.of(
                "shared_buffers", "work_mem", "effective_cache_size", "random_page_cost", "jit", "autovacuum_naptime");
        Map<String, String> settings = new LinkedHashMap<>();
        for (String name : names) {
            settings.put(name, queryScalar("show " + name));
        }
        return Map.copyOf(settings);
    }

    private BackgroundSnapshot captureBackground() throws SQLException {
        Map<String, long[]> tables = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("""
                        select relname, autovacuum_count, autoanalyze_count
                        from pg_stat_user_tables
                        where schemaname = 'public' and relname in ('instances', 'links')
                        """)) {
            while (rs.next()) {
                tables.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
            }
        }
        Map<String, long[]> gc = new LinkedHashMap<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.put(bean.getName(), new long[] {bean.getCollectionCount(), bean.getCollectionTime()});
        }
        return new BackgroundSnapshot(tables, gc);
    }

    private Path writeReport(
            WarehouseCorpus.Loaded loaded,
            BenchRun first,
            BenchRun second,
            long loadNanos,
            TableSizes sizes,
            String postgresVersion,
            Map<String, String> pgSettings,
            boolean smoke)
            throws IOException {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Path report;
        if (smoke) {
            report = Path.of("").toAbsolutePath().resolve("target").resolve("instances-poc-smoke.md");
        } else {
            Path dir = repositoryRoot().resolve("docs").resolve("perf");
            report = nextReportPath(dir, "instances-poc-" + today + ".md");
        }
        Files.createDirectories(report.getParent());
        Files.writeString(
                report, reportText(today, loaded, first, second, loadNanos, sizes, postgresVersion, pgSettings, smoke));
        return report;
    }

    private static String reportText(
            LocalDate today,
            WarehouseCorpus.Loaded loaded,
            BenchRun first,
            BenchRun second,
            long loadNanos,
            TableSizes sizes,
            String postgresVersion,
            Map<String, String> pgSettings,
            boolean smoke) {
        StringBuilder text = new StringBuilder();
        text.append("# 实例库 PoC 基准（").append(today);
        if (smoke) {
            text.append("，冒烟");
        }
        text.append("）\n\n");
        text.append("测量 P6-31 `InstanceStore` / `TraceQueries` 与 Q4/Q5 区间连接 SQL。本卡只测量，不做优化。\n\n");

        text.append("## 机器配置\n\n");
        text.append("| 项 | 值 |\n| --- | --- |\n");
        text.append("| CPU | ").append(cpuModel()).append(" |\n");
        text.append("| 逻辑处理器 | ")
                .append(Runtime.getRuntime().availableProcessors())
                .append(" |\n");
        text.append("| 内存 | ").append(memoryTotal()).append(" |\n");
        text.append("| JDK | ")
                .append(System.getProperty("java.version"))
                .append(" (")
                .append(System.getProperty("java.vm.name"))
                .append(") |\n");
        text.append("| -Xmx | ").append(xmxArgument()).append(" |\n");
        text.append("| PostgreSQL | ").append(postgresVersion).append(" (Testcontainers postgres:18) |\n");
        for (Map.Entry<String, String> entry : pgSettings.entrySet()) {
            text.append("| ")
                    .append(entry.getKey())
                    .append(" | ")
                    .append(entry.getValue())
                    .append(" |\n");
        }

        text.append("\n## 语料\n\n");
        WarehouseCorpus.Scale scale = loaded.scale();
        text.append("| 项 | 值 |\n| --- | --- |\n");
        text.append("| 模式 | ").append(smoke ? "冒烟（1/100）" : "全量").append(" |\n");
        text.append("| 货位 | ").append(grouped(scale.slots())).append(" |\n");
        text.append("| 传感器 | ").append(grouped(scale.sensors())).append(" |\n");
        text.append("| 堆垛机 / AGV | ")
                .append(scale.stackers())
                .append(" / ")
                .append(scale.agvs())
                .append(" |\n");
        text.append("| 批次 / 托盘 | ")
                .append(grouped(scale.batches()))
                .append(" / ")
                .append(grouped(scale.trays()))
                .append(" |\n");
        text.append("| 任务 | ")
                .append(grouped(scale.totalTasks()))
                .append("（每天 ")
                .append(grouped(scale.tasksPerDay()))
                .append(" × ")
                .append(scale.days())
                .append(" 天） |\n");
        text.append("| 环境事件 | ").append(grouped(scale.events())).append(" |\n");
        text.append("| COPY 耗时 | ").append(seconds(loadNanos)).append(" |\n");
        text.append("| instances 行数 / 体积 | ")
                .append(grouped(sizes.instances()))
                .append(" / ")
                .append(sizes.instanceBytes())
                .append(" |\n");
        text.append("| links 行数 / 体积 | ")
                .append(grouped(sizes.links()))
                .append(" / ")
                .append(sizes.linkBytes())
                .append(" |\n");

        text.append("\n### 采样参数（固定种子）\n\n");
        text.append("```\n").append(loaded.samples()).append("\n```\n");

        text.append("\n## 查询结果\n\n");
        text.append("| 查询 | p50 | p95 | p99 | max | 行数（两次） | 门禁 | 判定 |\n");
        text.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        appendQueryRow(text, "Q1", first.q1(), second.q1().rowCount(), Q123_P95_LIMIT_NANOS, smoke);
        appendQueryRow(text, "Q2", first.q2(), second.q2().rowCount(), Q123_P95_LIMIT_NANOS, smoke);
        appendQueryRow(text, "Q3", first.q3(), second.q3().rowCount(), Q123_P95_LIMIT_NANOS, smoke);
        appendQueryRow(text, "Q4", first.q4(), second.q4().rowCount(), Q45_P95_LIMIT_NANOS, smoke);
        appendQueryRow(text, "Q5", first.q5(), second.q5().rowCount(), Q45_P95_LIMIT_NANOS, smoke);

        text.append("\n### W1 写入\n\n");
        WriteRun w1 = first.w1();
        text.append("| 项 | 值 | 门禁 | 判定 |\n| --- | --- | --- | --- |\n");
        text.append("| 持续 | ").append(w1.durationMs()).append(" ms | — | — |\n");
        text.append("| 操作数 | ").append(grouped(w1.ops())).append(" | — | — |\n");
        text.append("| 吞吐 | ")
                .append(String.format(Locale.ROOT, "%.1f 次/秒", w1.throughput()))
                .append(" | ≥ 200 | ")
                .append(smoke ? "冒烟" : mark(w1.throughput() >= W1_MIN_THROUGHPUT))
                .append(" |\n");
        text.append("| 单次 p95 | ")
                .append(millis(w1.p95Nanos()))
                .append(" | ≤ 50 毫秒 | ")
                .append(smoke ? "冒烟" : mark(w1.p95Nanos() <= W1_P95_LIMIT_NANOS))
                .append(" |\n");

        text.append("\n## 门禁结论\n\n");
        if (smoke) {
            text.append("冒烟模式不判定门禁。\n");
        } else {
            List<String> failures = gateFailures(first);
            text.append("| 项 | 测量值 | 判定 |\n| --- | --- | --- |\n");
            text.append("| Q1 p95 | ")
                    .append(millis(first.q1().p95Nanos()))
                    .append(" | ")
                    .append(mark(first.q1().p95Nanos() <= Q123_P95_LIMIT_NANOS))
                    .append(" |\n");
            text.append("| Q2 p95 | ")
                    .append(millis(first.q2().p95Nanos()))
                    .append(" | ")
                    .append(mark(first.q2().p95Nanos() <= Q123_P95_LIMIT_NANOS))
                    .append(" |\n");
            text.append("| Q3 p95 | ")
                    .append(millis(first.q3().p95Nanos()))
                    .append(" | ")
                    .append(mark(first.q3().p95Nanos() <= Q123_P95_LIMIT_NANOS))
                    .append(" |\n");
            text.append("| Q4 p95 | ")
                    .append(millis(first.q4().p95Nanos()))
                    .append(" | ")
                    .append(mark(first.q4().p95Nanos() <= Q45_P95_LIMIT_NANOS))
                    .append(" |\n");
            text.append("| Q5 p95 | ")
                    .append(millis(first.q5().p95Nanos()))
                    .append(" | ")
                    .append(mark(first.q5().p95Nanos() <= Q45_P95_LIMIT_NANOS))
                    .append(" |\n");
            text.append("| W1 吞吐 | ")
                    .append(String.format(Locale.ROOT, "%.1f 次/秒", w1.throughput()))
                    .append(" | ")
                    .append(mark(w1.throughput() >= W1_MIN_THROUGHPUT))
                    .append(" |\n");
            text.append("| W1 p95 | ")
                    .append(millis(w1.p95Nanos()))
                    .append(" | ")
                    .append(mark(w1.p95Nanos() <= W1_P95_LIMIT_NANOS))
                    .append(" |\n");
            text.append("| 两次行数一致 | 相同 | 达标 |\n\n");
            if (failures.isEmpty()) {
                text.append("结论：全部达标，可继续 P6-33。\n");
            } else {
                text.append("结论：存在未达标项：").append(String.join("；", failures)).append("。\n");
            }
        }

        text.append("\n## 测法\n\n");
        text.append("- 造数：`WarehouseCorpus`，种子 ")
                .append(WarehouseCorpus.SEED)
                .append("，PostgreSQL `COPY`；写入后 `ANALYZE`。\n");
        text.append("- Q1 ~ Q3：`TraceQueries`；每条查询预热 ")
                .append(smoke ? SMOKE_WARMUP : WARMUP)
                .append(" 次、采样 ")
                .append(smoke ? SMOKE_SAMPLES : SAMPLES)
                .append(" 次。\n");
        text.append("- Q4 / Q5：基准内命名 SQL 常量（全文见下）；p50/p95/p99/max 用最近秩。\n");
        text.append("- W1：8 线程，upsert:unlink+link = 7:3，持续 ")
                .append(smoke ? "5" : "60")
                .append(" 秒。\n");
        text.append("- 造数后跑两轮查询，断言各查询返回行数相同。\n");

        text.append("\n## SQL\n\n");
        text.append("### Q4_DEEP_TRACE_SQL\n\n```sql\n")
                .append(Q4_DEEP_TRACE_SQL)
                .append("\n```\n\n");
        text.append("### Q5_REVERSE_TRACE_SQL\n\n```sql\n")
                .append(Q5_REVERSE_TRACE_SQL)
                .append("\n```\n\n");

        text.append("## 执行计划摘要\n\n");
        text.append("### Q1（1 跳 representative）\n\n```\n")
                .append(first.explainQ1())
                .append("\n```\n\n");
        text.append("### Q4\n\n```\n").append(first.explainQ4()).append("\n```\n\n");
        text.append("### Q5\n\n```\n").append(first.explainQ5()).append("\n```\n\n");

        text.append("## 采样期间后台活动（第一轮）\n\n");
        appendBackground(text, "Q1", first.q1().background());
        appendBackground(text, "Q4", first.q4().background());
        appendBackground(text, "Q5", first.q5().background());
        return text.toString();
    }

    private static void appendQueryRow(
            StringBuilder text, String name, QueryRun run, int secondRows, long limitNanos, boolean smoke) {
        text.append("| ")
                .append(name)
                .append(" | ")
                .append(millis(run.p50Nanos()))
                .append(" | ")
                .append(millis(run.p95Nanos()))
                .append(" | ")
                .append(millis(run.p99Nanos()))
                .append(" | ")
                .append(millis(run.maxNanos()))
                .append(" | ")
                .append(run.rowCount())
                .append(" / ")
                .append(secondRows)
                .append(" | ≤ ")
                .append(limitNanos / 1_000_000L)
                .append(" 毫秒 | ")
                .append(smoke ? "冒烟" : mark(run.p95Nanos() <= limitNanos))
                .append(" |\n");
    }

    private static void appendBackground(StringBuilder text, String label, BackgroundDelta delta) {
        text.append("- ")
                .append(label)
                .append(": autovacuum Δ=")
                .append(delta.autovacuumDelta())
                .append(", autoanalyze Δ=")
                .append(delta.autoanalyzeDelta())
                .append(", GC counts Δ=")
                .append(delta.gcCountDelta())
                .append("\n");
    }

    private static List<String> gateFailures(BenchRun run) {
        List<String> failures = new ArrayList<>();
        if (run.q1().p95Nanos() > Q123_P95_LIMIT_NANOS) {
            failures.add("Q1 p95 " + millis(run.q1().p95Nanos()) + " > 200 毫秒");
        }
        if (run.q2().p95Nanos() > Q123_P95_LIMIT_NANOS) {
            failures.add("Q2 p95 " + millis(run.q2().p95Nanos()) + " > 200 毫秒");
        }
        if (run.q3().p95Nanos() > Q123_P95_LIMIT_NANOS) {
            failures.add("Q3 p95 " + millis(run.q3().p95Nanos()) + " > 200 毫秒");
        }
        if (run.q4().p95Nanos() > Q45_P95_LIMIT_NANOS) {
            failures.add("Q4 p95 " + millis(run.q4().p95Nanos()) + " > 1 秒");
        }
        if (run.q5().p95Nanos() > Q45_P95_LIMIT_NANOS) {
            failures.add("Q5 p95 " + millis(run.q5().p95Nanos()) + " > 1 秒");
        }
        if (run.w1().throughput() < W1_MIN_THROUGHPUT) {
            failures.add("W1 吞吐 " + String.format(Locale.ROOT, "%.1f", run.w1().throughput()) + " < 200 次/秒");
        }
        if (run.w1().p95Nanos() > W1_P95_LIMIT_NANOS) {
            failures.add("W1 p95 " + millis(run.w1().p95Nanos()) + " > 50 毫秒");
        }
        return failures;
    }

    static Path nextReportPath(Path dir, String baseName) {
        Path candidate = dir.resolve(baseName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String stem = baseName.endsWith(".md") ? baseName.substring(0, baseName.length() - 3) : baseName;
        for (int suffix = 2; ; suffix++) {
            Path next = dir.resolve(stem + "-" + suffix + ".md");
            if (!Files.exists(next)) {
                return next;
            }
        }
    }

    private DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static long percentile(long[] samples, double fraction) {
        if (samples.length == 0) {
            return 0L;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static long maxOf(long[] samples) {
        if (samples.length == 0) {
            return 0L;
        }
        long max = samples[0];
        for (long sample : samples) {
            if (sample > max) {
                max = sample;
            }
        }
        return max;
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("mvnw")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "repository root not found from " + Path.of("").toAbsolutePath());
    }

    private static String cpuModel() {
        return firstMatchingLine(Path.of("/proc/cpuinfo"), "model name", "unknown");
    }

    private static String memoryTotal() {
        return firstMatchingLine(Path.of("/proc/meminfo"), "MemTotal:", "unknown");
    }

    private static String firstMatchingLine(Path file, String prefix, String fallback) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.filter(line -> line.startsWith(prefix))
                    .map(line -> {
                        int colon = line.indexOf(':');
                        return colon < 0
                                ? line.trim()
                                : line.substring(colon + 1).trim();
                    })
                    .findFirst()
                    .orElse(fallback);
        } catch (IOException ex) {
            return fallback;
        }
    }

    private static String xmxArgument() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-Xmx"))
                .findFirst()
                .orElse("未在 JVM 参数中出现");
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f 秒", nanos / 1_000_000_000d);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f 毫秒", nanos / 1_000_000d);
    }

    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String mark(boolean ok) {
        return ok ? "达标" : "未达标";
    }

    @FunctionalInterface
    private interface QueryIntSupplier {
        int getAsInt() throws Exception;
    }

    private record QueryRun(
            long[] nanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos,
            int rowCount,
            BackgroundDelta background) {}

    private record WriteRun(
            long ops, double throughput, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos, long durationMs) {}

    private record QueryBundle(QueryRun q1, QueryRun q2, QueryRun q3, QueryRun q4, QueryRun q5) {}

    private record BenchRun(
            QueryRun q1,
            QueryRun q2,
            QueryRun q3,
            QueryRun q4,
            QueryRun q5,
            WriteRun w1,
            String explainQ1,
            String explainQ4,
            String explainQ5) {}

    private record TableSizes(long instances, long links, String instanceBytes, String linkBytes) {}

    private record BackgroundSnapshot(Map<String, long[]> tables, Map<String, long[]> gc) {}

    private record BackgroundDelta(long autovacuumDelta, long autoanalyzeDelta, long gcCountDelta) {
        static BackgroundDelta of(BackgroundSnapshot before, BackgroundSnapshot after) {
            long vac = 0;
            long ana = 0;
            for (String table : List.of("instances", "links")) {
                long[] b = before.tables().getOrDefault(table, new long[] {0, 0});
                long[] a = after.tables().getOrDefault(table, new long[] {0, 0});
                vac += a[0] - b[0];
                ana += a[1] - b[1];
            }
            long gc = 0;
            for (Map.Entry<String, long[]> entry : after.gc().entrySet()) {
                long[] b = before.gc().getOrDefault(entry.getKey(), new long[] {0, 0});
                gc += entry.getValue()[0] - b[0];
            }
            return new BackgroundDelta(vac, ana, gc);
        }
    }
}
