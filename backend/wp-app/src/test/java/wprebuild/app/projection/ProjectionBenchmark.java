package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;

/**
 * Projection-table PoC benchmark (P6-11).
 *
 * <p>Skipped unless {@code -Dbench=true}. Measures compute, JDBC replace, row counts, three SQL
 * query latencies, and rebuild idempotency digests. After writing the report, fails when the 50 万
 * gates are missed (unlike {@code KernelBenchmark}, which only records).
 */
@DatabaseTest
@EnabledIfSystemProperty(named = "bench", matches = "true")
class ProjectionBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ProjectionBenchmark.class);

    private static final int QUERY_SAMPLES = 100;

    private static final long REBUILD_LIMIT_NANOS = 60L * 1_000_000_000L;

    private static final long QUERY_P95_LIMIT_NANOS = 200L * 1_000_000L;

    private static final List<String> TABLE_DIGESTS = List.of(
            "entities", "labels", "hierarchy_edges", "property_domains", "property_ranges");

    private final ProjectionWriter writer;

    private final JdbcClient jdbc;

    private final PlatformTransactionManager transactions;

    ProjectionBenchmark(ProjectionWriter writer, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.writer = writer;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.HOURS)
    void writes_the_projection_poc_report() throws IOException {
        String postgres = jdbc.sql("show server_version").query(String.class).single();
        System.gc();
        ScaleReport small = measure(ProjectionCorpus.small());
        System.gc();
        ScaleReport large = measure(ProjectionCorpus.large());
        Path report = writeReport(List.of(small, large), postgres);

        assertThat(report).isRegularFile();
        assertThat(report.getFileName().toString()).isEqualTo("projection-poc-" + LocalDate.now() + ".md");
        String text = Files.readString(report);
        assertThat(text).contains("CPU", "JDK", "-Xmx", "PostgreSQL", "达标");

        List<String> failures = gateFailures(large);
        assertThat(failures)
                .withFailMessage("projection PoC gates missed after report %s: %s", report, failures)
                .isEmpty();
        log.info("report {} gates pass", report);
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

        RowCounts counts = rowCounts(corpus.projectId());

        // (d) before (e): table-wide md5(string_agg) is heavy and must not skew query samples.
        log.info("queries {}", corpus.target());
        long[] q1 = sampleQuery(QUERY_SAMPLES, () -> runQ1(corpus));
        long[] q2 = sampleQuery(QUERY_SAMPLES, () -> runQ2(corpus));
        long[] q3 = sampleQuery(QUERY_SAMPLES, () -> runQ3(corpus));

        log.info("idempotency digests {}", corpus.target());
        Map<String, String> firstDigests = tableDigests(corpus.projectId());
        log.info("second rebuild {}", corpus.target());
        ProjectionSnapshot again = ProjectionRows.compute(corpus.runtime());
        replace(corpus.projectId(), again, 2L);
        Map<String, String> secondDigests = tableDigests(corpus.projectId());
        boolean digestsMatch = firstDigests.equals(secondDigests);

        return new ScaleReport(
                corpus.target(),
                corpus.classCount(),
                corpus.axiomCount(),
                computeNanos,
                writeNanos,
                counts,
                percentile(q1, 0.50d),
                percentile(q1, 0.95d),
                percentile(q2, 0.50d),
                percentile(q2, 0.95d),
                percentile(q3, 0.50d),
                percentile(q3, 0.95d),
                digestsMatch,
                firstDigests,
                secondDigests);
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
                        """)
                .param(projectId)
                .query(String.class)
                .single();
    }

    private String digestLabels(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            iri || chr(31) || property_iri || chr(31) || lang || chr(31) || value,
                            chr(10) order by iri, property_iri, lang, value), ''))
                        from projection.labels
                        where project_id = ?
                        """)
                .param(projectId)
                .query(String.class)
                .single();
    }

    private String digestHierarchy(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            hierarchy || chr(31) || child_iri || chr(31) || parent_iri,
                            chr(10) order by hierarchy, child_iri, parent_iri), ''))
                        from projection.hierarchy_edges
                        where project_id = ?
                        """)
                .param(projectId)
                .query(String.class)
                .single();
    }

    private String digestDomains(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            property_iri || chr(31) || class_iri,
                            chr(10) order by property_iri, class_iri), ''))
                        from projection.property_domains
                        where project_id = ?
                        """)
                .param(projectId)
                .query(String.class)
                .single();
    }

    private String digestRanges(UUID projectId) {
        return jdbc.sql("""
                        select md5(coalesce(string_agg(
                            property_iri || chr(31) || range_iri || chr(31) || range_kind,
                            chr(10) order by property_iri, range_iri, range_kind), ''))
                        from projection.property_ranges
                        where project_id = ?
                        """)
                .param(projectId)
                .query(String.class)
                .single();
    }

    private void runQ1(ProjectionCorpus.Loaded corpus) {
        UUID id = corpus.projectId().value();
        jdbc.sql("""
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
                        """)
                .param(id)
                .param(corpus.depth3ClassIri())
                .param(id)
                .query(String.class)
                .list();
    }

    private void runQ2(ProjectionCorpus.Loaded corpus) {
        jdbc.sql("""
                        select iri
                        from projection.labels
                        where project_id = ?
                          and lower(value) like ?
                        limit 20
                        """)
                .param(corpus.projectId().value())
                .param(corpus.labelPrefix().toLowerCase(Locale.ROOT) + "%")
                .query(String.class)
                .list();
    }

    private void runQ3(ProjectionCorpus.Loaded corpus) {
        UUID id = corpus.projectId().value();
        String property = corpus.sampleObjectPropertyIri();
        jdbc.sql("""
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
                .list();
    }

    private static long[] sampleQuery(int samples, Runnable query) {
        long[] nanos = new long[samples];
        for (int index = 0; index < samples; index++) {
            long start = System.nanoTime();
            query.run();
            nanos[index] = System.nanoTime() - start;
        }
        return nanos;
    }

    private static long percentile(long[] samples, double fraction) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static List<String> gateFailures(ScaleReport large) {
        List<String> failures = new ArrayList<>();
        long rebuild = large.computeNanos() + large.writeNanos();
        if (rebuild > REBUILD_LIMIT_NANOS) {
            failures.add("全量重建(a+b) " + seconds(rebuild) + " > 60 秒");
        }
        if (large.q1P95Nanos() > QUERY_P95_LIMIT_NANOS) {
            failures.add("Q1 p95 " + millis(large.q1P95Nanos()) + " > 200 毫秒");
        }
        if (large.q2P95Nanos() > QUERY_P95_LIMIT_NANOS) {
            failures.add("Q2 p95 " + millis(large.q2P95Nanos()) + " > 200 毫秒");
        }
        if (large.q3P95Nanos() > QUERY_P95_LIMIT_NANOS) {
            failures.add("Q3 p95 " + millis(large.q3P95Nanos()) + " > 200 毫秒");
        }
        if (!large.digestsMatch()) {
            failures.add("两次重建 md5 不一致: " + large.firstDigests() + " vs " + large.secondDigests());
        }
        return failures;
    }

    private static Path writeReport(List<ScaleReport> scales, String postgresVersion) throws IOException {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Path report = repositoryRoot().resolve("docs").resolve("perf").resolve("projection-poc-" + today + ".md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, reportText(today, scales, postgresVersion));
        return report;
    }

    private static String reportText(LocalDate today, List<ScaleReport> scales, String postgresVersion) {
        StringBuilder text = new StringBuilder();
        text.append("# 投影表 PoC 基准（").append(today).append("）\n\n");
        text.append("测量 `ProjectionRows.compute`、`ProjectionWriter.replaceProject` 与三条典型 SQL 是否达到 P6-11 的初始通过标准。");
        text.append("本卡不做任何优化。\n\n");
        text.append(machineSection(postgresVersion));
        text.append(resultsTable(scales));
        text.append(gateSection(scales));
        text.append(methodSection(scales));
        return text.toString();
    }

    private static String machineSection(String postgresVersion) {
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
        text.append("| PostgreSQL | ")
                .append(postgresVersion)
                .append(" (Testcontainers postgres:18) |\n\n");
        return text.toString();
    }

    private static String resultsTable(List<ScaleReport> scales) {
        StringBuilder text = new StringBuilder();
        text.append("## 结果\n\n");
        text.append("| 规模 | 场景 | 测量值 | 门禁 | 判定 |\n");
        text.append("| --- | --- | --- | --- | --- |\n");
        for (ScaleReport scale : scales) {
            appendScaleRows(text, scale);
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
        return text.toString();
    }

    private static void appendScaleRows(StringBuilder text, ScaleReport scale) {
        String size = scaleLabel(scale);
        boolean gated = isLarge(scale);
        long rebuild = scale.computeNanos() + scale.writeNanos();
        row(text, size, "ProjectionRows.compute (a)", seconds(scale.computeNanos()), "无", "无门禁");
        row(text, size, "ProjectionWriter.replaceProject (b)", seconds(scale.writeNanos()), "无", "无门禁");
        row(
                text,
                size,
                "全量重建 (a+b)",
                seconds(rebuild),
                gated ? "≤ 60 秒" : "无",
                gated ? mark(rebuild <= REBUILD_LIMIT_NANOS) : "无门禁");
        row(text, size, "Q1 后代 p50", millis(scale.q1P50Nanos()), "无", "无门禁");
        row(
                text,
                size,
                "Q1 后代 p95",
                millis(scale.q1P95Nanos()),
                gated ? "≤ 200 毫秒" : "无",
                gated ? mark(scale.q1P95Nanos() <= QUERY_P95_LIMIT_NANOS) : "无门禁");
        row(text, size, "Q2 标签前缀 p50", millis(scale.q2P50Nanos()), "无", "无门禁");
        row(
                text,
                size,
                "Q2 标签前缀 p95",
                millis(scale.q2P95Nanos()),
                gated ? "≤ 200 毫秒" : "无",
                gated ? mark(scale.q2P95Nanos() <= QUERY_P95_LIMIT_NANOS) : "无门禁");
        row(text, size, "Q3 定义域/值域 p50", millis(scale.q3P50Nanos()), "无", "无门禁");
        row(
                text,
                size,
                "Q3 定义域/值域 p95",
                millis(scale.q3P95Nanos()),
                gated ? "≤ 200 毫秒" : "无",
                gated ? mark(scale.q3P95Nanos() <= QUERY_P95_LIMIT_NANOS) : "无门禁");
        row(
                text,
                size,
                "两次重建 md5 一致 (e)",
                scale.digestsMatch() ? "相同" : "不同",
                gated ? "相同" : "无",
                gated ? mark(scale.digestsMatch()) : "无门禁");
    }

    private static String gateSection(List<ScaleReport> scales) {
        ScaleReport large = scales.stream()
                .filter(ProjectionBenchmark::isLarge)
                .findFirst()
                .orElseThrow();
        List<String> failures = gateFailures(large);
        StringBuilder text = new StringBuilder();
        text.append("## 门禁结论\n\n");
        text.append("初始通过标准只看约 50 万公理规模。约 10 万规模用同一测法对照，不单独作为门槛。\n\n");
        text.append("| 项 | 测量值 | 判定 |\n");
        text.append("| --- | --- | --- |\n");
        long rebuild = large.computeNanos() + large.writeNanos();
        text.append("| 全量重建 (a+b) | ")
                .append(seconds(rebuild))
                .append(" | ")
                .append(mark(rebuild <= REBUILD_LIMIT_NANOS))
                .append(" |\n");
        text.append("| Q1 p95 | ")
                .append(millis(large.q1P95Nanos()))
                .append(" | ")
                .append(mark(large.q1P95Nanos() <= QUERY_P95_LIMIT_NANOS))
                .append(" |\n");
        text.append("| Q2 p95 | ")
                .append(millis(large.q2P95Nanos()))
                .append(" | ")
                .append(mark(large.q2P95Nanos() <= QUERY_P95_LIMIT_NANOS))
                .append(" |\n");
        text.append("| Q3 p95 | ")
                .append(millis(large.q3P95Nanos()))
                .append(" | ")
                .append(mark(large.q3P95Nanos() <= QUERY_P95_LIMIT_NANOS))
                .append(" |\n");
        text.append("| 两次重建 md5 | ")
                .append(large.digestsMatch() ? "相同" : "不同")
                .append(" | ")
                .append(mark(large.digestsMatch()))
                .append(" |\n\n");
        if (failures.isEmpty()) {
            text.append("结论：全部达标。\n\n");
        } else {
            text.append("结论：存在未达标项：");
            text.append(String.join("；", failures));
            text.append("。\n\n");
        }
        return text.toString();
    }

    private static String methodSection(List<ScaleReport> scales) {
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
        text.append("经内存 `RevisionLog` / `SnapshotStore` 与 `ProjectRuntimeLoader` 加载，不经上传导入。\n");
        text.append("- (a) `ProjectionRows.compute(runtime)`；(b) 同一事务内 `lockProject` + `replaceProject` 写入 Testcontainers PostgreSQL 18。\n");
        text.append("- (c) 各投影表 `count(*)`。\n");
        text.append("- (d) Q1 递归 CTE 查深度为 3 的类的全部后代；Q2 `lower(value) like 'class-12%'` `limit 20`；");
        text.append("Q3 查样本对象属性的定义域与值域。各 100 次，p50 / p95 用最近秩：排序后第 `ceil(p × n)` 个样本。\n");
        text.append("- (e) 连续重建两次，对各表按主键列排序后取 `md5(string_agg(...))`，比较是否相同。\n");
        text.append("- 比较的表：").append(String.join(", ", TABLE_DIGESTS)).append("。\n");
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

    private record RowCounts(long entities, long labels, long hierarchyEdges, long domains, long ranges) {}

    private record ScaleReport(
            String target,
            int classCount,
            long axiomCount,
            long computeNanos,
            long writeNanos,
            RowCounts counts,
            long q1P50Nanos,
            long q1P95Nanos,
            long q2P50Nanos,
            long q2P95Nanos,
            long q3P50Nanos,
            long q3P95Nanos,
            boolean digestsMatch,
            Map<String, String> firstDigests,
            Map<String, String> secondDigests) {}
}
