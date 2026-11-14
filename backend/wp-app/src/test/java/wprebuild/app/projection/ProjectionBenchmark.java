package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;

/**
 * Projection-table PoC benchmark (P6-11 / P6-18 method).
 *
 * <p>Skipped unless {@code -Dbench=true}. With {@code -Dbench.smoke=true}, both scales use ~2,000
 * classes, the report goes to {@code target/projection-poc-smoke.md}, and gates are not asserted.
 *
 * <p>Callers: Surefire with {@code -Dbench=true} (P6-11 / P6-18); {@link ProjectionReportPathTest}
 * calls {@link #nextReportPath(Path, String)}. Revises the existing P6-11 benchmark class in place.
 */
@DatabaseTest
@EnabledIfSystemProperty(named = "bench", matches = "true")
class ProjectionBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ProjectionBenchmark.class);

    private static final int QUERY_SAMPLES = 100;

    private static final int WARMUP_SAMPLES = 10;

    private static final long REBUILD_LIMIT_NANOS = 60L * 1_000_000_000L;

    private static final long QUERY_P95_LIMIT_NANOS = 200L * 1_000_000L;

    private static final List<String> TABLE_DIGESTS =
            List.of("entities", "labels", "hierarchy_edges", "property_domains", "property_ranges");

    private static final List<String> PROJECTION_TABLES =
            List.of("entities", "labels", "hierarchy_edges", "property_domains", "property_ranges");

    private static final List<String> PG_SETTINGS = List.of(
            "shared_buffers",
            "work_mem",
            "effective_cache_size",
            "random_page_cost",
            "jit",
            "jit_above_cost",
            "jit_inline_above_cost",
            "jit_optimize_above_cost",
            "autovacuum_naptime",
            "plan_cache_mode");

    /** Shared by {@code runQ1}, pre-refresh explain, and {@code PREPARE}. */
    static final String Q1_SQL = """
            with recursive descendants as (
                select child_iri as iri
                from projection.hierarchy_edges
                where project_id = ?
                  and hierarchy = 'CLASS'
                  and parent_iri = ?
                union
                select e.child_iri
                from projection.hierarchy_edges e
                join descendants d on e.parent_iri = d.iri
                where e.project_id = ?
                  and e.hierarchy = 'CLASS'
            )
            select iri from descendants
            """;

    private final ProjectionWriter writer;

    private final JdbcClient jdbc;

    private final JdbcTemplate jdbcTemplate;

    private final PlatformTransactionManager transactions;

    ProjectionBenchmark(
            ProjectionWriter writer,
            JdbcClient jdbc,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactions) {
        this.writer = writer;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.HOURS)
    void writes_the_projection_poc_report() throws IOException {
        boolean smoke = Boolean.getBoolean("bench.smoke");
        String postgres = jdbc.sql("show server_version").query(String.class).single();
        Map<String, String> pgSettings = readPgSettings();
        int prepareThreshold = readPrepareThreshold();

        System.gc();
        ScaleReport first = measure(smoke ? ProjectionCorpus.smoke() : ProjectionCorpus.small());
        System.gc();
        ScaleReport second = measure(smoke ? ProjectionCorpus.smoke() : ProjectionCorpus.large());
        Path report = writeReport(List.of(first, second), postgres, pgSettings, prepareThreshold, smoke);

        assertThat(report).isRegularFile();
        String text = Files.readString(report);
        if (smoke) {
            assertThat(report.getFileName().toString()).isEqualTo("projection-poc-smoke.md");
            assertSmokeReport(text);
            log.info("smoke report {} (gates not asserted)", report);
            return;
        }

        assertThat(report.getFileName().toString()).matches("projection-poc-\\d{4}-\\d{2}-\\d{2}(-\\d+)?\\.md");
        assertThat(text).contains("CPU", "JDK", "-Xmx", "PostgreSQL", "达标");

        List<String> failures = gateFailures(second);
        assertThat(failures)
                .withFailMessage("projection PoC gates missed after report %s: %s", report, failures)
                .isEmpty();
        log.info("report {} gates pass", report);
    }

    private static void assertSmokeReport(String text) {
        assertThat(text).contains("重建后立即（刷新前）");
        assertThat(text).contains("刷新并预热后（定制计划）");
        assertThat(text).contains("刷新并预热后（通用计划）");
        assertThat(text).contains("pg_stats");
        assertThat(text).contains("generic_plans");
        assertThat(text).contains("custom_plans");
        assertThat(text).contains("冷样本");
        assertThat(text).contains("Q1 样本序列");
        assertThat(text).contains("采样期间后台活动");
    }

    private ScaleReport measure(ProjectionCorpus.Loaded corpus) {
        log.info("compute {}", corpus.target());
        long computeStart = System.nanoTime();
        ProjectionSnapshot snapshot = ProjectionRows.compute(corpus.runtime());
        long computeNanos = System.nanoTime() - computeStart;

        log.info("replace {}", corpus.target());
        long writeStart = System.nanoTime();
        replace(corpus.projectId(), snapshot, 1L);
        long writeNanos = System.nanoTime() - writeStart;

        PgStatsSnapshot pgStats = readProjectIdStats();
        Map<String, Instant> lastAutoanalyzeBefore = readLastAutoanalyze();
        String explainBefore = explainQ1(jdbc, corpus);
        QueryRun q1Before =
                sampleWithBackground("Q1-刷新前", () -> sampleQueryBundle(QUERY_SAMPLES, () -> runQ1(jdbc, corpus)));

        log.info("refreshStatistics {}", corpus.target());
        long analyzeStart = System.nanoTime();
        writer.refreshStatistics();
        long analyzeNanos = System.nanoTime() - analyzeStart;

        RowCounts counts = rowCounts(corpus.projectId());

        log.info("queries {}", corpus.target());
        AfterRefreshQueries afterRefresh = sampleAfterRefresh(corpus);

        log.info("idempotency digests {}", corpus.target());
        Map<String, String> firstDigests = tableDigests(corpus.projectId());
        log.info("second rebuild {}", corpus.target());
        ProjectionSnapshot again = ProjectionRows.compute(corpus.runtime());
        replace(corpus.projectId(), again, 2L);
        long secondAnalyzeStart = System.nanoTime();
        writer.refreshStatistics();
        long secondAnalyzeNanos = System.nanoTime() - secondAnalyzeStart;
        Map<String, String> secondDigests = tableDigests(corpus.projectId());
        boolean digestsMatch = firstDigests.equals(secondDigests);

        return new ScaleReport(
                corpus.target(),
                corpus.classCount(),
                corpus.axiomCount(),
                computeNanos,
                writeNanos,
                analyzeNanos,
                secondAnalyzeNanos,
                counts,
                pgStats,
                lastAutoanalyzeBefore,
                explainBefore,
                q1Before,
                afterRefresh.q1(),
                afterRefresh.q1Cold(),
                afterRefresh.prepared(),
                afterRefresh.q2(),
                afterRefresh.q2().cold(),
                afterRefresh.q3(),
                afterRefresh.q3().cold(),
                afterRefresh.explainCustom(),
                afterRefresh.explainGeneric(),
                digestsMatch,
                firstDigests,
                secondDigests);
    }

    private QueryRun sampleWarmed(String label, QueryIntSupplier query) {
        long[] coldNanos = sampleQuery(WARMUP_SAMPLES, query);
        ColdSamples cold = ColdSamples.of(coldNanos);
        QueryRun run = sampleWithBackground(label, () -> sampleQueryBundle(QUERY_SAMPLES, query));
        return run.withCold(cold);
    }

    /**
     * Holds one JDBC connection for Q1 warmup/sample, {@code pg_prepared_statements} delta, and the
     * two forced {@code PREPARE}/{@code EXPLAIN EXECUTE} plans. Q2/Q3 use the pool while that
     * connection stays checked out, matching the card order: all sampling → plan counts → plans.
     */
    private AfterRefreshQueries sampleAfterRefresh(ProjectionCorpus.Loaded corpus) {
        return jdbcTemplate.execute((Connection con) -> {
            SingleConnectionDataSource ds = new SingleConnectionDataSource(con, true);
            JdbcClient pinned = JdbcClient.create(ds);
            // Drop prepares left on a reused pooled connection so counts are for this run only.
            pinned.sql("deallocate all").update();
            PreparedPlanCounts preparedBefore = readPreparedPlanCounts(pinned);

            long[] coldNanos = sampleQuery(WARMUP_SAMPLES, () -> runQ1(pinned, corpus));
            ColdSamples q1Cold = ColdSamples.of(coldNanos);
            BackgroundSnapshot before = captureBackground();
            Instant sampleStart = Instant.now();
            SampleBundle bundle = sampleQueryBundle(QUERY_SAMPLES, () -> runQ1(pinned, corpus));
            Instant sampleEnd = Instant.now();
            BackgroundSnapshot after = captureBackground();
            QueryRun q1 = new QueryRun(
                    bundle.nanos(),
                    percentile(bundle.nanos(), 0.50d),
                    percentile(bundle.nanos(), 0.95d),
                    percentile(bundle.nanos(), 0.99d),
                    maxOf(bundle.nanos()),
                    bundle.rowCount(),
                    sampleStart,
                    sampleEnd,
                    BackgroundDelta.of(before, after),
                    q1Cold);
            log.info("Q1 pinned sampled {} rows", bundle.rowCount());

            QueryRun q2 = sampleWarmed("Q2", () -> runQ2(jdbc, corpus));
            QueryRun q3 = sampleWarmed("Q3", () -> runQ3(jdbc, corpus));

            PreparedPlanCounts preparedAfter = readPreparedPlanCounts(pinned);
            PreparedPlanCounts prepared = preparedAfter.minus(preparedBefore);
            PostWarmPlans plans = collectPostWarmPlans(ds, corpus);
            return new AfterRefreshQueries(q1, q1Cold, prepared, q2, q3, plans.customPlan(), plans.genericPlan());
        });
    }

    private PostWarmPlans collectPostWarmPlans(SingleConnectionDataSource ds, ProjectionCorpus.Loaded corpus) {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
        TransactionTemplate tx = new TransactionTemplate(tm);
        return tx.execute(status -> {
            JdbcClient client = JdbcClient.create(ds);
            client.sql("prepare wp_q1(uuid, text, uuid) as " + q1PrepareSql()).update();
            client.sql("set local plan_cache_mode = force_custom_plan").update();
            String custom = explainExecuteWpQ1(client, corpus);
            client.sql("set local plan_cache_mode = force_generic_plan").update();
            String generic = explainExecuteWpQ1(client, corpus);
            client.sql("deallocate wp_q1").update();
            return new PostWarmPlans(custom, generic);
        });
    }

    private String explainExecuteWpQ1(JdbcClient client, ProjectionCorpus.Loaded corpus) {
        UUID id = corpus.projectId().value();
        String sql = "explain (analyze, buffers, settings) execute wp_q1("
                + literalUuid(id)
                + ", "
                + literalText(corpus.depth3ClassIri())
                + ", "
                + literalUuid(id)
                + ")";
        return client.sql(sql).query(String.class).list().stream().collect(Collectors.joining("\n"));
    }

    private static String literalUuid(UUID id) {
        return "'" + id + "'::uuid";
    }

    private static String literalText(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String q1PrepareSql() {
        String sql = Q1_SQL;
        sql = sql.replaceFirst("\\?", "\\$1");
        sql = sql.replaceFirst("\\?", "\\$2");
        sql = sql.replaceFirst("\\?", "\\$3");
        return sql;
    }

    private void replace(ProjectId projectId, ProjectionSnapshot snapshot, long revNo) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Boolean ok = tx.execute(status -> {
            writer.lockProject(projectId);
            return writer.replaceProject(projectId, snapshot, revNo);
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("replaceProject rejected rev " + revNo + " for " + projectId);
        }
    }

    private RowCounts rowCounts(ProjectId projectId) {
        UUID id = projectId.value();
        return new RowCounts(
                count("entities", id),
                count("labels", id),
                count("hierarchy_edges", id),
                count("property_domains", id),
                count("property_ranges", id));
    }

    private long count(String table, UUID projectId) {
        return jdbc.sql("select count(*) from projection." + table + " where project_id = ?")
                .param(projectId)
                .query(Long.class)
                .single();
    }

    private Map<String, String> tableDigests(ProjectId projectId) {
        UUID id = projectId.value();
        Map<String, String> digests = new LinkedHashMap<>();
        digests.put("entities", digestEntities(id));
        digests.put("labels", digestLabels(id));
        digests.put("hierarchy_edges", digestHierarchy(id));
        digests.put("property_domains", digestDomains(id));
        digests.put("property_ranges", digestRanges(id));
        return Map.copyOf(digests);
    }

    private String digestEntities(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            iri || chr(31) || entity_type || chr(31) || deprecated::text,
                            chr(10) order by iri, entity_type), ''))
                        from projection.entities
                        where project_id = ?
                        """).param(projectId).query(String.class).single();
    }

    private String digestLabels(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            iri || chr(31) || property_iri || chr(31) || lang || chr(31) || value,
                            chr(10) order by iri, property_iri, lang, value), ''))
                        from projection.labels
                        where project_id = ?
                        """).param(projectId).query(String.class).single();
    }

    private String digestHierarchy(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            hierarchy || chr(31) || child_iri || chr(31) || parent_iri,
                            chr(10) order by hierarchy, child_iri, parent_iri), ''))
                        from projection.hierarchy_edges
                        where project_id = ?
                        """).param(projectId).query(String.class).single();
    }

    private String digestDomains(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            property_iri || chr(31) || class_iri,
                            chr(10) order by property_iri, class_iri), ''))
                        from projection.property_domains
                        where project_id = ?
                        """).param(projectId).query(String.class).single();
    }

    private String digestRanges(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            property_iri || chr(31) || range_iri || chr(31) || range_kind,
                            chr(10) order by property_iri, range_iri, range_kind), ''))
                        from projection.property_ranges
                        where project_id = ?
                        """).param(projectId).query(String.class).single();
    }

    private int runQ1(JdbcClient client, ProjectionCorpus.Loaded corpus) {
        UUID id = corpus.projectId().value();
        return client.sql(Q1_SQL)
                .param(id)
                .param(corpus.depth3ClassIri())
                .param(id)
                .query(String.class)
                .list()
                .size();
    }

    private int runQ2(JdbcClient client, ProjectionCorpus.Loaded corpus) {
        return client.sql("""
                        select iri
                        from projection.labels
                        where project_id = ?
                          and lower(value) like ?
                        limit 20
                        """)
                .param(corpus.projectId().value())
                .param(corpus.labelPrefix().toLowerCase(Locale.ROOT) + "%")
                .query(String.class)
                .list()
                .size();
    }

    private int runQ3(JdbcClient client, ProjectionCorpus.Loaded corpus) {
        UUID id = corpus.projectId().value();
        String property = corpus.sampleObjectPropertyIri();
        return client.sql("""
                        select class_iri as value
                        from projection.property_domains
                        where project_id = ? and property_iri = ?
                        union all
                        select range_iri
                        from projection.property_ranges
                        where project_id = ? and property_iri = ?
                        """)
                .param(id)
                .param(property)
                .param(id)
                .param(property)
                .query(String.class)
                .list()
                .size();
    }

    private String explainQ1(JdbcClient client, ProjectionCorpus.Loaded corpus) {
        UUID id = corpus.projectId().value();
        return client
                .sql("explain (analyze, buffers, settings) " + Q1_SQL)
                .param(id)
                .param(corpus.depth3ClassIri())
                .param(id)
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.joining("\n"));
    }

    private PgStatsSnapshot readProjectIdStats() {
        return jdbc.sql("""
                        select n_distinct,
                               most_common_vals::text as most_common_vals,
                               most_common_freqs::text as most_common_freqs
                        from pg_stats
                        where schemaname = 'projection'
                          and tablename = 'hierarchy_edges'
                          and attname = 'project_id'
                        """)
                .query((rs, rowNum) -> new PgStatsSnapshot(
                        rs.getObject("n_distinct") == null
                                ? "null"
                                : rs.getObject("n_distinct").toString(),
                        rs.getString("most_common_vals") == null ? "null" : rs.getString("most_common_vals"),
                        rs.getString("most_common_freqs") == null ? "null" : rs.getString("most_common_freqs")))
                .optional()
                .orElse(new PgStatsSnapshot("missing", "missing", "missing"));
    }

    private Map<String, Instant> readLastAutoanalyze() {
        Map<String, Instant> values = new LinkedHashMap<>();
        jdbc.sql("""
                        select relname, last_autoanalyze
                        from pg_stat_user_tables
                        where schemaname = 'projection'
                          and relname in ('entities', 'labels', 'hierarchy_edges',
                                          'property_domains', 'property_ranges')
                        """)
                .query((rs, rowNum) -> {
                    java.sql.Timestamp ts = rs.getTimestamp("last_autoanalyze");
                    values.put(rs.getString("relname"), ts == null ? null : ts.toInstant());
                    return null;
                })
                .list();
        for (String table : PROJECTION_TABLES) {
            values.putIfAbsent(table, null);
        }
        // last_autoanalyze may be null; Map.copyOf rejects null values.
        return Collections.unmodifiableMap(values);
    }

    private PreparedPlanCounts readPreparedPlanCounts(JdbcClient client) {
        List<PreparedPlanCounts> rows = client.sql("""
                        select coalesce(sum(generic_plans), 0)::bigint as generic_plans,
                               coalesce(sum(custom_plans), 0)::bigint as custom_plans
                        from pg_prepared_statements
                        where statement like '%hierarchy_edges%'
                          and statement like '%descendants%'
                        """)
                .query((rs, rowNum) -> new PreparedPlanCounts(rs.getLong("generic_plans"), rs.getLong("custom_plans")))
                .list();
        if (rows.isEmpty()) {
            log.warn("no pg_prepared_statements rows matched Q1");
            return new PreparedPlanCounts(0, 0);
        }
        return rows.get(0);
    }

    private Map<String, String> readPgSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String name : PG_SETTINGS) {
            settings.put(name, jdbc.sql("show " + name).query(String.class).single());
        }
        return Map.copyOf(settings);
    }

    private int readPrepareThreshold() {
        Integer value = jdbcTemplate.execute((Connection con) -> {
            try {
                PGConnection pg = con.unwrap(PGConnection.class);
                return pg.getPrepareThreshold();
            } catch (SQLException ex) {
                throw new IllegalStateException("prepareThreshold", ex);
            }
        });
        return value == null ? -1 : value;
    }

    private QueryRun sampleWithBackground(String label, SampleSupplier supplier) {
        BackgroundSnapshot before = captureBackground();
        Instant start = Instant.now();
        SampleBundle bundle = supplier.get();
        Instant end = Instant.now();
        BackgroundSnapshot after = captureBackground();
        log.info("{} sampled {} rows", label, bundle.rowCount());
        return new QueryRun(
                bundle.nanos(),
                percentile(bundle.nanos(), 0.50d),
                percentile(bundle.nanos(), 0.95d),
                percentile(bundle.nanos(), 0.99d),
                maxOf(bundle.nanos()),
                bundle.rowCount(),
                start,
                end,
                BackgroundDelta.of(before, after),
                null);
    }

    private BackgroundSnapshot captureBackground() {
        Map<String, TableStat> tables = new LinkedHashMap<>();
        jdbc.sql("""
                        select relname, autovacuum_count, autoanalyze_count,
                               last_autovacuum, last_autoanalyze
                        from pg_stat_user_tables
                        where schemaname = 'projection'
                          and relname in ('entities', 'labels', 'hierarchy_edges',
                                          'property_domains', 'property_ranges')
                        """)
                .query((rs, rowNum) -> {
                    java.sql.Timestamp vac = rs.getTimestamp("last_autovacuum");
                    java.sql.Timestamp ana = rs.getTimestamp("last_autoanalyze");
                    tables.put(
                            rs.getString("relname"),
                            new TableStat(
                                    rs.getLong("autovacuum_count"),
                                    rs.getLong("autoanalyze_count"),
                                    vac == null ? null : vac.toInstant(),
                                    ana == null ? null : ana.toInstant()));
                    return null;
                })
                .list();
        RelClassStats edges = jdbc.sql("""
                        select c.relpages, c.reltuples, c.relallvisible
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = 'projection' and c.relname = 'hierarchy_edges'
                        """)
                .query((rs, rowNum) -> new RelClassStats(
                        rs.getLong("relpages"), rs.getDouble("reltuples"), rs.getLong("relallvisible")))
                .single();
        Map<String, GcStat> gc = new LinkedHashMap<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.put(bean.getName(), new GcStat(bean.getCollectionCount(), bean.getCollectionTime()));
        }
        return new BackgroundSnapshot(Map.copyOf(tables), edges, Map.copyOf(gc));
    }

    private static SampleBundle sampleQueryBundle(int samples, QueryIntSupplier query) {
        long[] nanos = new long[samples];
        int rows = 0;
        for (int index = 0; index < samples; index++) {
            long start = System.nanoTime();
            rows = query.getAsInt();
            nanos[index] = System.nanoTime() - start;
        }
        return new SampleBundle(nanos, rows);
    }

    private static long[] sampleQuery(int samples, QueryIntSupplier query) {
        return sampleQueryBundle(samples, query).nanos();
    }

    private static long percentile(long[] samples, double fraction) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static long maxOf(long[] samples) {
        long max = samples[0];
        for (long sample : samples) {
            if (sample > max) {
                max = sample;
            }
        }
        return max;
    }

    private static List<String> gateFailures(ScaleReport large) {
        List<String> failures = new ArrayList<>();
        long rebuild = large.computeNanos() + large.writeNanos() + large.analyzeNanos();
        if (rebuild > REBUILD_LIMIT_NANOS) {
            failures.add("全量重建(a+b+b2) " + seconds(rebuild) + " > 60 秒");
        }
        if (large.q1().p95Nanos() > QUERY_P95_LIMIT_NANOS) {
            failures.add("Q1 p95 " + millis(large.q1().p95Nanos()) + " > 200 毫秒");
        }
        if (large.q2().p95Nanos() > QUERY_P95_LIMIT_NANOS) {
            failures.add("Q2 p95 " + millis(large.q2().p95Nanos()) + " > 200 毫秒");
        }
        if (large.q3().p95Nanos() > QUERY_P95_LIMIT_NANOS) {
            failures.add("Q3 p95 " + millis(large.q3().p95Nanos()) + " > 200 毫秒");
        }
        if (!large.digestsMatch()) {
            failures.add("两次重建 md5 不一致: " + large.firstDigests() + " vs " + large.secondDigests());
        }
        return failures;
    }

    /**
     * Chooses the next report path without overwriting an existing file: {@code baseName}, then
     * {@code stem-2.md}, {@code stem-3.md}, …
     */
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

    private static Path writeReport(
            List<ScaleReport> scales,
            String postgresVersion,
            Map<String, String> pgSettings,
            int prepareThreshold,
            boolean smoke)
            throws IOException {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Path report;
        if (smoke) {
            report = Path.of("").toAbsolutePath().resolve("target").resolve("projection-poc-smoke.md");
        } else {
            Path dir = repositoryRoot().resolve("docs").resolve("perf");
            report = nextReportPath(dir, "projection-poc-" + today + ".md");
        }
        Files.createDirectories(report.getParent());
        Files.writeString(report, reportText(today, scales, postgresVersion, pgSettings, prepareThreshold, smoke));
        return report;
    }

    private static String reportText(
            LocalDate today,
            List<ScaleReport> scales,
            String postgresVersion,
            Map<String, String> pgSettings,
            int prepareThreshold,
            boolean smoke) {
        StringBuilder text = new StringBuilder();
        text.append("# 投影表 PoC 基准（").append(today);
        if (smoke) {
            text.append("，冒烟");
        }
        text.append("）\n\n");
        text.append("测量 `ProjectionRows.compute`、`ProjectionWriter.replaceProject`、统计信息刷新与三条典型 SQL。");
        text.append("本卡不做任何优化。\n\n");
        text.append(machineSection(postgresVersion, pgSettings, prepareThreshold));
        text.append(resultsTable(scales, smoke));
        text.append(gateSection(scales, smoke));
        text.append(methodSection(scales, smoke));
        text.append(backgroundSection(scales));
        text.append(sampleAppendix(scales));
        text.append(explainSection(scales));
        return text.toString();
    }

    private static String machineSection(String postgresVersion, Map<String, String> pgSettings, int prepareThreshold) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        StringBuilder text = new StringBuilder();
        text.append("## 机器配置\n\n");
        text.append("| 项 | 值 |\n");
        text.append("| --- | --- |\n");
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
        text.append("| 堆上限 Runtime.maxMemory | ").append(bytes(heap.getMax())).append(" |\n");
        text.append("| PostgreSQL | ").append(postgresVersion).append(" (Testcontainers postgres:18) |\n");
        for (Map.Entry<String, String> entry : pgSettings.entrySet()) {
            text.append("| ")
                    .append(entry.getKey())
                    .append(" | ")
                    .append(entry.getValue())
                    .append(" |\n");
        }
        text.append("| pgjdbc prepareThreshold | ").append(prepareThreshold).append(" |\n\n");
        return text.toString();
    }

    private static String resultsTable(List<ScaleReport> scales, boolean smoke) {
        StringBuilder text = new StringBuilder();
        text.append("## 结果\n\n");
        text.append("| 规模 | 场景 | 测量值 | 门禁 | 判定 |\n");
        text.append("| --- | --- | --- | --- | --- |\n");
        for (ScaleReport scale : scales) {
            appendScaleRows(text, scale, smoke);
        }
        text.append('\n');
        text.append("### 行数\n\n");
        text.append("| 规模 | entities | labels | hierarchy_edges | property_domains | property_ranges |\n");
        text.append("| --- | --- | --- | --- | --- | --- |\n");
        for (ScaleReport scale : scales) {
            RowCounts c = scale.counts();
            text.append("| ")
                    .append(scaleLabel(scale))
                    .append(" | ")
                    .append(grouped(c.entities()))
                    .append(" | ")
                    .append(grouped(c.labels()))
                    .append(" | ")
                    .append(grouped(c.hierarchyEdges()))
                    .append(" | ")
                    .append(grouped(c.domains()))
                    .append(" | ")
                    .append(grouped(c.ranges()))
                    .append(" |\n");
        }
        text.append('\n');
        text.append("### 刷新前 pg_stats（hierarchy_edges.project_id）\n\n");
        text.append("| 规模 | n_distinct | most_common_vals | most_common_freqs |\n");
        text.append("| --- | --- | --- | --- |\n");
        for (ScaleReport scale : scales) {
            PgStatsSnapshot s = scale.pgStats();
            text.append("| ")
                    .append(scaleLabel(scale))
                    .append(" | ")
                    .append(s.nDistinct())
                    .append(" | ")
                    .append(escapeCell(s.mostCommonVals()))
                    .append(" | ")
                    .append(escapeCell(s.mostCommonFreqs()))
                    .append(" |\n");
        }
        text.append('\n');
        return text.toString();
    }

    private static void appendScaleRows(StringBuilder text, ScaleReport scale, boolean smoke) {
        String size = scaleLabel(scale);
        boolean gated = !smoke && isLarge(scale);
        long rebuild = scale.computeNanos() + scale.writeNanos() + scale.analyzeNanos();
        row(text, size, "ProjectionRows.compute (a)", seconds(scale.computeNanos()), "无", "无门禁");
        row(text, size, "ProjectionWriter.replaceProject (b)", seconds(scale.writeNanos()), "无", "无门禁");
        row(text, size, "统计信息刷新 (b2)", seconds(scale.analyzeNanos()), "无", "无门禁");
        row(
                text,
                size,
                "全量重建 (a+b+b2)",
                seconds(rebuild),
                gated ? "≤ 60 秒" : "无",
                gated ? mark(rebuild <= REBUILD_LIMIT_NANOS) : "无门禁");
        row(text, size, "第二次重建统计信息刷新 (b2)", seconds(scale.secondAnalyzeNanos()), "无", "无门禁");
        appendQueryRows(text, size, "Q1 刷新前", scale.q1Before(), false);
        appendQueryRows(text, size, "Q1 后代", scale.q1(), gated);
        if (scale.q1Cold() != null) {
            row(text, size, "Q1 冷样本 max", millis(scale.q1Cold().maxNanos()), "无", "无门禁");
            row(text, size, "Q1 冷样本中位数", millis(scale.q1Cold().medianNanos()), "无", "无门禁");
        }
        row(
                text,
                size,
                "Q1 prepared custom_plans / generic_plans",
                scale.prepared().customPlans() + " / " + scale.prepared().genericPlans(),
                "无",
                "无门禁");
        row(text, size, "Q1 返回行数", grouped(scale.q1().rowCount()), "无", "无门禁");
        appendQueryRows(text, size, "Q2 标签前缀", scale.q2(), gated);
        if (scale.q2Cold() != null) {
            row(text, size, "Q2 冷样本 max", millis(scale.q2Cold().maxNanos()), "无", "无门禁");
            row(text, size, "Q2 冷样本中位数", millis(scale.q2Cold().medianNanos()), "无", "无门禁");
        }
        row(text, size, "Q2 返回行数", grouped(scale.q2().rowCount()), "无", "无门禁");
        appendQueryRows(text, size, "Q3 定义域/值域", scale.q3(), gated);
        if (scale.q3Cold() != null) {
            row(text, size, "Q3 冷样本 max", millis(scale.q3Cold().maxNanos()), "无", "无门禁");
            row(text, size, "Q3 冷样本中位数", millis(scale.q3Cold().medianNanos()), "无", "无门禁");
        }
        row(text, size, "Q3 返回行数", grouped(scale.q3().rowCount()), "无", "无门禁");
        row(
                text,
                size,
                "两次重建 md5 一致 (e)",
                scale.digestsMatch() ? "相同" : "不同",
                gated ? "相同" : "无",
                gated ? mark(scale.digestsMatch()) : "无门禁");
    }

    private static void appendQueryRows(StringBuilder text, String size, String label, QueryRun run, boolean gated) {
        row(text, size, label + " p50", millis(run.p50Nanos()), "无", "无门禁");
        row(
                text,
                size,
                label + " p95",
                millis(run.p95Nanos()),
                gated ? "≤ 200 毫秒" : "无",
                gated ? mark(run.p95Nanos() <= QUERY_P95_LIMIT_NANOS) : "无门禁");
        row(text, size, label + " p99", millis(run.p99Nanos()), "无", "无门禁");
        row(text, size, label + " max", millis(run.maxNanos()), "无", "无门禁");
    }

    private static String gateSection(List<ScaleReport> scales, boolean smoke) {
        StringBuilder text = new StringBuilder();
        text.append("## 门禁结论\n\n");
        if (smoke) {
            text.append("冒烟模式不判定门禁；下列数字仅供核对报告管道。\n\n");
        } else {
            text.append("初始通过标准只看约 50 万公理规模。约 10 万规模用同一测法对照，不单独作为门槛。\n\n");
            text.append("**门禁语义（P6-18）**：全量重建为首次重建的 (a + b + b2)；");
            text.append("查询门禁用刷新统计信息并预热之后的 100 个样本判定。");
            text.append("刷新前采样与冷样本只记录，供人对照；是否接受该语义由人在 P6-11 重跑时确认。\n\n");
        }
        ScaleReport gatedScale = smoke
                ? scales.get(scales.size() - 1)
                : scales.stream()
                        .filter(ProjectionBenchmark::isLarge)
                        .findFirst()
                        .orElseThrow();
        List<String> failures = smoke ? List.of() : gateFailures(gatedScale);
        text.append("| 项 | 测量值 | 判定 |\n");
        text.append("| --- | --- | --- |\n");
        long rebuild = gatedScale.computeNanos() + gatedScale.writeNanos() + gatedScale.analyzeNanos();
        text.append("| 全量重建 (a+b+b2) | ")
                .append(seconds(rebuild))
                .append(" | ")
                .append(smoke ? "冒烟" : mark(rebuild <= REBUILD_LIMIT_NANOS))
                .append(" |\n");
        text.append("| Q1 p95（预热后） | ")
                .append(millis(gatedScale.q1().p95Nanos()))
                .append(" | ")
                .append(smoke ? "冒烟" : mark(gatedScale.q1().p95Nanos() <= QUERY_P95_LIMIT_NANOS))
                .append(" |\n");
        text.append("| Q1 刷新前 p95 | ")
                .append(millis(gatedScale.q1Before().p95Nanos()))
                .append(" | 只记录 |\n");
        if (gatedScale.q1Cold() != null) {
            text.append("| Q1 冷样本 max / 中位数 | ")
                    .append(millis(gatedScale.q1Cold().maxNanos()))
                    .append(" / ")
                    .append(millis(gatedScale.q1Cold().medianNanos()))
                    .append(" | 只记录 |\n");
        }
        text.append("| Q2 p95（预热后） | ")
                .append(millis(gatedScale.q2().p95Nanos()))
                .append(" | ")
                .append(smoke ? "冒烟" : mark(gatedScale.q2().p95Nanos() <= QUERY_P95_LIMIT_NANOS))
                .append(" |\n");
        text.append("| Q3 p95（预热后） | ")
                .append(millis(gatedScale.q3().p95Nanos()))
                .append(" | ")
                .append(smoke ? "冒烟" : mark(gatedScale.q3().p95Nanos() <= QUERY_P95_LIMIT_NANOS))
                .append(" |\n");
        text.append("| 两次重建 md5 | ")
                .append(gatedScale.digestsMatch() ? "相同" : "不同")
                .append(" | ")
                .append(smoke ? "冒烟" : mark(gatedScale.digestsMatch()))
                .append(" |\n\n");
        if (smoke) {
            text.append("结论：冒烟报告已写出，未判定门禁。\n\n");
        } else if (failures.isEmpty()) {
            text.append("结论：全部达标。\n\n");
        } else {
            text.append("结论：存在未达标项：");
            text.append(String.join("；", failures));
            text.append("。\n\n");
        }
        return text.toString();
    }

    private static String methodSection(List<ScaleReport> scales, boolean smoke) {
        StringBuilder text = new StringBuilder();
        text.append("## 测法\n\n");
        text.append("- 语料：`ProjectionCorpus`，类树与 `KernelBenchmark` 相同（分支系数 ")
                .append(ProjectionCorpus.BRANCHING)
                .append("，每类 1 条 `rdfs:label`（en），种子 ")
                .append(ProjectionCorpus.SEED)
                .append("），类数 ");
        text.append(scales.get(0).classCount())
                .append(" 与 ")
                .append(scales.get(1).classCount());
        text.append("；另加 ")
                .append(ProjectionCorpus.OBJECT_PROPERTY_COUNT)
                .append(" 个对象属性与 ")
                .append(ProjectionCorpus.DATA_PROPERTY_COUNT)
                .append(" 个数据属性，各带定义域与值域。");
        if (smoke) {
            text.append("本报告为冒烟模式（`-Dbench.smoke=true`）。");
        }
        text.append("经内存 `RevisionLog` / `SnapshotStore` 与 `ProjectRuntimeLoader` 加载，不经上传导入。\n");
        text.append(
                "- (a) `ProjectionRows.compute(runtime)`；(b) 同一事务内 `lockProject` + `replaceProject` 写入 Testcontainers PostgreSQL 18。\n");
        text.append("- (b2) 每次 `replace` 提交后调用 `ProjectionWriter.refreshStatistics()`（`ANALYZE` 五张投影表），耗时单独记录；");
        text.append("门禁用的全量重建耗时是**首次重建**的 (a + b + b2)。\n");
        text.append("- 刷新前：读取 `pg_stats` 中 `hierarchy_edges.project_id` 的统计与各表 `last_autoanalyze`；");
        text.append("执行一次 Q1 的 `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`；不预热，直接采样 Q1 100 次（只记录）。\n");
        text.append("- (c) 刷新之后对各投影表 `count(*)`。\n");
        text.append("- (d) 每条查询先预热 ")
                .append(WARMUP_SAMPLES)
                .append(" 次（冷样本：max / 中位数，不判定），再采样 ")
                .append(QUERY_SAMPLES)
                .append(" 次；门禁用预热后的样本。");
        text.append("Q1 的预热与采样固定在同一 JDBC 连接上；Q2/Q3 预热采样期间该连接保持占用。");
        text.append("全部采样结束后，在该连接上读取本会话 `pg_prepared_statements` 相对本轮起点的 `custom_plans` / `generic_plans` 增量");
        text.append("（先 `DEALLOCATE ALL` 清掉连接复用残留），再在同一连接同一事务内 `PREPARE wp_q1`，");
        text.append(
                "分别 `force_custom_plan` 与 `force_generic_plan` 采集两份 `EXPLAIN (ANALYZE, BUFFERS, SETTINGS) EXECUTE`。");
        text.append("p50 / p95 / p99 / max 用最近秩：排序后第 `ceil(p × n)` 个样本（p99 为第 99 个，max 为第 100 个）。\n");
        text.append(
                "- 每条查询（含刷新前 Q1）采样前后读取 `pg_stat_user_tables`、`hierarchy_edges` 的 `pg_class`（`relpages` / `reltuples` / `relallvisible`）与各 `GarbageCollectorMXBean`。\n");
        text.append("- (e) 连续重建两次（第二次重建后同样刷新统计信息），对各表按主键列排序后取 `md5(string_agg(...))`，比较是否相同；摘要比较放在全部采样之后。\n");
        text.append("- 比较的表：").append(String.join(", ", TABLE_DIGESTS)).append("。\n");
        text.append("- 报告文件已存在时不覆盖，依次使用 `-2`、`-3` 后缀（冒烟报告固定写到 `target/projection-poc-smoke.md`）。\n\n");
        return text.toString();
    }

    private static String backgroundSection(List<ScaleReport> scales) {
        StringBuilder text = new StringBuilder();
        text.append("## 采样期间后台活动\n\n");
        for (ScaleReport scale : scales) {
            text.append("### ").append(scaleLabel(scale)).append("\n\n");
            text.append("| 查询 | Δautovacuum | Δautoanalyze | Δrelpages | Δreltuples | Δrelallvisible | GC |\n");
            text.append("| --- | --- | --- | --- | --- | --- | --- |\n");
            appendBackgroundRow(text, "Q1 刷新前", scale.q1Before().background());
            appendBackgroundRow(text, "Q1", scale.q1().background());
            appendBackgroundRow(text, "Q2", scale.q2().background());
            appendBackgroundRow(text, "Q3", scale.q3().background());
            text.append('\n');
            text.append("刷新前各表 last_autoanalyze：");
            text.append(formatLastAutoanalyze(scale.lastAutoanalyzeBefore()));
            text.append("\n\n");
        }
        return text.toString();
    }

    private static void appendBackgroundRow(StringBuilder text, String label, BackgroundDelta delta) {
        text.append("| ")
                .append(label)
                .append(" | ")
                .append(delta.autovacuumDelta())
                .append(" | ")
                .append(delta.autoanalyzeDelta())
                .append(" | ")
                .append(delta.relpagesDelta())
                .append(" | ")
                .append(delta.reltuplesDelta())
                .append(" | ")
                .append(delta.relallvisibleDelta())
                .append(" | ")
                .append(escapeCell(delta.gcSummary()))
                .append(" |\n");
    }

    private static String formatLastAutoanalyze(Map<String, Instant> values) {
        return PROJECTION_TABLES.stream()
                .map(table -> table + "=" + (values.get(table) == null ? "null" : values.get(table)))
                .collect(Collectors.joining(", "));
    }

    private static String sampleAppendix(List<ScaleReport> scales) {
        StringBuilder text = new StringBuilder();
        text.append("## 附录：Q1 样本序列\n\n");
        text.append("耗时单位：毫秒；每行 10 个样本。慢样本连成一段通常表示计划切换；零散分布通常表示干扰。\n\n");
        for (ScaleReport scale : scales) {
            text.append("### ").append(scaleLabel(scale)).append(" — 刷新前\n\n");
            appendSampleBlock(text, scale.q1Before());
            text.append("### ").append(scaleLabel(scale)).append(" — 刷新并预热后\n\n");
            appendSampleBlock(text, scale.q1());
        }
        return text.toString();
    }

    private static void appendSampleBlock(StringBuilder text, QueryRun run) {
        text.append("采样开始：")
                .append(run.sampleStart())
                .append("；结束：")
                .append(run.sampleEnd())
                .append("\n\n");
        text.append("```\n");
        long[] samples = run.samples();
        for (int index = 0; index < samples.length; index++) {
            if (index > 0 && index % 10 == 0) {
                text.append('\n');
            } else if (index > 0) {
                text.append(' ');
            }
            text.append(String.format(Locale.ROOT, "%.2f", samples[index] / 1_000_000d));
        }
        text.append("\n```\n\n");
    }

    private static String explainSection(List<ScaleReport> scales) {
        StringBuilder text = new StringBuilder();
        text.append("## 执行计划\n\n");
        for (ScaleReport scale : scales) {
            text.append("### ").append(scaleLabel(scale)).append("\n\n");
            text.append("#### 重建后立即（刷新前）\n\n");
            text.append("```\n").append(scale.explainBefore()).append("\n```\n\n");
            text.append("#### 刷新并预热后（定制计划）\n\n");
            text.append("```\n").append(scale.explainCustom()).append("\n```\n\n");
            text.append("#### 刷新并预热后（通用计划）\n\n");
            text.append("```\n").append(scale.explainGeneric()).append("\n```\n\n");
        }
        return text.toString();
    }

    private static boolean isLarge(ScaleReport scale) {
        return scale.target().contains("50");
    }

    private static String scaleLabel(ScaleReport scale) {
        return scale.target() + "（" + grouped(scale.axiomCount()) + " 公理）";
    }

    private static void row(StringBuilder text, String size, String scene, String value, String gate, String verdict) {
        text.append("| ")
                .append(size)
                .append(" | ")
                .append(scene)
                .append(" | ")
                .append(value)
                .append(" | ")
                .append(gate)
                .append(" | ")
                .append(verdict)
                .append(" |\n");
    }

    private static String mark(boolean pass) {
        return pass ? "达标" : "未达标";
    }

    private static String escapeCell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
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

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f 秒", nanos / 1_000_000_000d);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f 毫秒", nanos / 1_000_000d);
    }

    private static String bytes(long value) {
        return String.format(Locale.ROOT, "%.2f GiB（%d 字节）", value / 1024d / 1024d / 1024d, value);
    }

    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    @FunctionalInterface
    private interface QueryIntSupplier {
        int getAsInt();
    }

    @FunctionalInterface
    private interface SampleSupplier {
        SampleBundle get();
    }

    private record SampleBundle(long[] nanos, int rowCount) {}

    private record ColdSamples(long maxNanos, long medianNanos) {
        static ColdSamples of(long[] samples) {
            return new ColdSamples(maxOf(samples), percentile(samples, 0.50d));
        }
    }

    private record PreparedPlanCounts(long genericPlans, long customPlans) {
        PreparedPlanCounts minus(PreparedPlanCounts earlier) {
            return new PreparedPlanCounts(genericPlans - earlier.genericPlans(), customPlans - earlier.customPlans());
        }
    }

    private record PostWarmPlans(String customPlan, String genericPlan) {}

    private record AfterRefreshQueries(
            QueryRun q1,
            ColdSamples q1Cold,
            PreparedPlanCounts prepared,
            QueryRun q2,
            QueryRun q3,
            String explainCustom,
            String explainGeneric) {}

    private record PgStatsSnapshot(String nDistinct, String mostCommonVals, String mostCommonFreqs) {}

    private record TableStat(
            long autovacuumCount, long autoanalyzeCount, Instant lastAutovacuum, Instant lastAutoanalyze) {}

    private record RelClassStats(long relpages, double reltuples, long relallvisible) {}

    private record GcStat(long count, long timeMillis) {}

    private record BackgroundSnapshot(
            Map<String, TableStat> tables, RelClassStats hierarchyEdges, Map<String, GcStat> gc) {}

    private record BackgroundDelta(
            String autovacuumDelta,
            String autoanalyzeDelta,
            long relpagesDelta,
            String reltuplesDelta,
            long relallvisibleDelta,
            String gcSummary) {
        static BackgroundDelta of(BackgroundSnapshot before, BackgroundSnapshot after) {
            StringBuilder vac = new StringBuilder();
            StringBuilder ana = new StringBuilder();
            for (String table : PROJECTION_TABLES) {
                TableStat b = before.tables().getOrDefault(table, new TableStat(0, 0, null, null));
                TableStat a = after.tables().getOrDefault(table, new TableStat(0, 0, null, null));
                if (!vac.isEmpty()) {
                    vac.append(", ");
                }
                vac.append(table).append(':').append(a.autovacuumCount() - b.autovacuumCount());
                if (!ana.isEmpty()) {
                    ana.append(", ");
                }
                ana.append(table).append(':').append(a.autoanalyzeCount() - b.autoanalyzeCount());
            }
            RelClassStats edgesBefore = before.hierarchyEdges();
            RelClassStats edgesAfter = after.hierarchyEdges();
            long pagesDelta = edgesAfter.relpages() - edgesBefore.relpages();
            String tuplesDelta = String.format(Locale.ROOT, "%.1f", edgesAfter.reltuples() - edgesBefore.reltuples());
            long relDelta = edgesAfter.relallvisible() - edgesBefore.relallvisible();
            StringBuilder gc = new StringBuilder();
            for (String name : after.gc().keySet()) {
                GcStat b = before.gc().getOrDefault(name, new GcStat(0, 0));
                GcStat a = after.gc().getOrDefault(name, new GcStat(0, 0));
                if (!gc.isEmpty()) {
                    gc.append("; ");
                }
                gc.append(name)
                        .append(" Δcount=")
                        .append(a.count() - b.count())
                        .append(" Δms=")
                        .append(a.timeMillis() - b.timeMillis());
            }
            return new BackgroundDelta(
                    vac.toString(), ana.toString(), pagesDelta, tuplesDelta, relDelta, gc.toString());
        }
    }

    private record QueryRun(
            long[] samples,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos,
            int rowCount,
            Instant sampleStart,
            Instant sampleEnd,
            BackgroundDelta background,
            ColdSamples cold) {
        QueryRun withCold(ColdSamples value) {
            return new QueryRun(
                    samples,
                    p50Nanos,
                    p95Nanos,
                    p99Nanos,
                    maxNanos,
                    rowCount,
                    sampleStart,
                    sampleEnd,
                    background,
                    value);
        }
    }

    private record RowCounts(long entities, long labels, long hierarchyEdges, long domains, long ranges) {}

    private record ScaleReport(
            String target,
            int classCount,
            long axiomCount,
            long computeNanos,
            long writeNanos,
            long analyzeNanos,
            long secondAnalyzeNanos,
            RowCounts counts,
            PgStatsSnapshot pgStats,
            Map<String, Instant> lastAutoanalyzeBefore,
            String explainBefore,
            QueryRun q1Before,
            QueryRun q1,
            ColdSamples q1Cold,
            PreparedPlanCounts prepared,
            QueryRun q2,
            ColdSamples q2Cold,
            QueryRun q3,
            ColdSamples q3Cold,
            String explainCustom,
            String explainGeneric,
            boolean digestsMatch,
            Map<String, String> firstDigests,
            Map<String, String> secondDigests) {}
}
