package wprebuild.kernel.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnLineChangeCodec;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.hierarchy.AnnotationPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.hierarchy.DataPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ObjectPropertyHierarchyView;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.DerivedView;
import wprebuild.kernel.runtime.FreshIriMinter;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;
import wprebuild.kernel.shortform.DisplayNameSetting;
import wprebuild.kernel.shortform.DisplayNameView;
import wprebuild.kernel.testsupport.SyntheticOntologyGenerator;

/**
 * ADR-005 / ADR-011 baseline (P1-20).
 *
 * <p>Skipped unless the test JVM has {@code -Dbench=true}. A missed gate is written into the report
 * as 未达标 and does not fail the test: the card keeps the report and leaves the threshold to a person.
 * Replay time is the in-memory log read plus {@code apply}, the same loop as {@code ProjectRuntimeLoader},
 * and does not include snapshot decode or the one-shot view rebuild.
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class KernelBenchmark {

    private static final Logger log = LoggerFactory.getLogger(KernelBenchmark.class);

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final int REVISIONS = 1_000;

    private static final int CHANGES_PER_REVISION = 10;

    private static final int REPLAY_CHANGES = REVISIONS * CHANGES_PER_REVISION;

    private static final int EDITS = 1_000;

    private static final int LINE_COUNT = 100_000;

    private static final int BRANCHING = 4;

    private static final int LABEL_LANGUAGES = 1;

    private static final long SEED = 20L;

    private static final long DECODE_LIMIT_NANOS = 30L * 1_000_000_000L;

    private static final long HEAP_LIMIT_BYTES = (long) (2.5d * (1L << 30));

    private static final long EDIT_P95_LIMIT_NANOS = 100L * 1_000_000L;

    private static final double REPLAY_MIN_PER_SECOND = 5_000d;

    private static final double LINE_MIN_PER_SECOND = 20_000d;

    private static final Instant REPLAY_STAMP = Instant.parse("2026-11-05T00:00:00Z");

    @Test
    @Timeout(value = 3, unit = TimeUnit.HOURS)
    void writes_the_kernel_benchmark_report() throws IOException {
        LineReport lines = measureLines();
        System.gc();
        ScaleReport small = measureScale(33_333, "约 10 万");
        System.gc();
        ScaleReport large = measureScale(166_667, "约 50 万");
        Path report = writeReport(List.of(small, large), lines);

        assertThat(report).isRegularFile();
        assertThat(report.getFileName().toString()).isEqualTo("kernel-benchmark-" + LocalDate.now() + ".md");
        String text = Files.readString(report);
        assertThat(text).contains("CPU", "JDK", "-Xmx", "达标");
        assertThat(small.axiomCount()).isEqualTo(99_998L);
        assertThat(large.axiomCount()).isEqualTo(500_000L);
        assertThat(lines.decodedLines()).isEqualTo(LINE_COUNT);
        log.info("report {} gates {}", report, officialGatesPass(large, lines) ? "pass" : "fail");
    }

    private static ScaleReport measureScale(int classCount, String target) {
        log.info("generate {} classes for {}", classCount, target);
        SyntheticOntologyGenerator.Parameters parameters =
                new SyntheticOntologyGenerator.Parameters(classCount, BRANCHING, LABEL_LANGUAGES, 0, 0, SEED);
        MutableProjectOntologies source = importGenerated(SyntheticOntologyGenerator.generate(parameters));
        long axiomCount = source.axiomCount();
        SnapshotCodec codec = new OfnZipSnapshotCodec();

        log.info("encode {}", target);
        long encodeStart = System.nanoTime();
        byte[] payload = codec.encode(source);
        long encodeNanos = System.nanoTime() - encodeStart;
        source = null;
        System.gc();

        log.info("decode {}", target);
        long decodeStart = System.nanoTime();
        MutableProjectOntologies decoded = codec.decode(payload);
        long decodeNanos = System.nanoTime() - decodeStart;
        if (decoded.axiomCount() != axiomCount) {
            throw new IllegalStateException("decoded axiom count " + decoded.axiomCount() + " != " + axiomCount);
        }
        decoded = null;
        System.gc();

        log.info("replay {}", target);
        MutableProjectOntologies replayed = codec.decode(payload);
        long replayNanos = timeReplay(replayed, axiomCount);
        replayed = null;
        System.gc();

        log.info("load {}", target);
        Loaded loaded = loadRuntime(payload, codec);
        payload = null;
        long heapBytes = heapUsed();
        log.info("edits {}", target);
        long[] edits = timeEdits(loaded, classCount);
        return new ScaleReport(
                target,
                classCount,
                axiomCount,
                encodeNanos,
                decodeNanos,
                replayNanos,
                percentile(edits, 0.50d),
                percentile(edits, 0.95d),
                heapBytes);
    }

    private static MutableProjectOntologies importGenerated(OWLOntology ontology) {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(new DeclareOntology(
                ROOT,
                ontology.getOntologyID().getOntologyIRI(),
                ontology.getOntologyID().getVersionIRI())));
        addAxioms(project, ontology);
        ontology.getOWLOntologyManager().removeOntology(ontology);
        return project;
    }

    private static void addAxioms(MutableProjectOntologies project, OWLOntology ontology) {
        List<OntologyChange> batch = new ArrayList<>(10_000);
        ontology.axioms().forEach(axiom -> {
            batch.add(new AddAxiom(ROOT, axiom));
            if (batch.size() == 10_000) {
                project.apply(batch);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            project.apply(batch);
        }
    }

    /** Times log read plus apply. The decoded ontology is the caller's, and is not retained here. */
    private static long timeReplay(MutableProjectOntologies ontologies, long baseAxioms) {
        ProjectId projectId = ProjectId.random();
        InMemoryRevisionLog revisionLog = new InMemoryRevisionLog();
        fillReplayLog(revisionLog, projectId, ontologies.dataFactory());
        long start = System.nanoTime();
        int applied = applyReplay(ontologies, revisionLog, projectId);
        long elapsed = System.nanoTime() - start;
        if (applied != REPLAY_CHANGES || ontologies.axiomCount() != baseAxioms + applied) {
            throw new IllegalStateException("replay applied " + applied + " axioms, count " + ontologies.axiomCount());
        }
        return elapsed;
    }

    private static void fillReplayLog(InMemoryRevisionLog revisionLog, ProjectId projectId, OWLDataFactory data) {
        RevisionNumber expected = RevisionNumber.ZERO;
        for (int number = 1; number <= REVISIONS; number++) {
            Revision revision = new Revision(
                    RevisionNumber.of(number), UserId.SYSTEM, REPLAY_STAMP, "replay", replayChanges(data, number));
            revisionLog.append(projectId, expected, revision);
            expected = revision.number();
        }
    }

    private static int applyReplay(
            MutableProjectOntologies ontologies, InMemoryRevisionLog revisionLog, ProjectId projectId) {
        int applied = 0;
        try (Stream<Revision> revisions =
                revisionLog.read(projectId, RevisionNumber.of(1), RevisionNumber.of(REVISIONS))) {
            for (Revision revision : revisions.toList()) {
                ontologies.apply(revision.changes());
                applied += revision.changes().size();
            }
        }
        return applied;
    }

    private static List<OntologyChange> replayChanges(OWLDataFactory data, int revision) {
        List<OntologyChange> changes = new ArrayList<>(CHANGES_PER_REVISION);
        for (int index = 0; index < CHANGES_PER_REVISION; index++) {
            IRI iri = IRI.create("http://example.org/wp-test/replay#R" + revision + "C" + index);
            changes.add(new AddAxiom(ROOT, data.getOWLDeclarationAxiom(data.getOWLClass(iri))));
        }
        return List.copyOf(changes);
    }

    private static Loaded loadRuntime(byte[] payload, SnapshotCodec codec) {
        ProjectId projectId = ProjectId.random();
        InMemoryRevisionLog revisionLog = new InMemoryRevisionLog();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        store.save(projectId, RevisionNumber.ZERO, codec.id(), payload);
        ProjectRuntime runtime = new ProjectRuntimeLoader(revisionLog, store, codec, viewSuppliers()).load(projectId);
        requireViews(runtime);
        return new Loaded(runtime, revisionLog);
    }

    private static List<Supplier<DerivedView>> viewSuppliers() {
        IRI label = OWLManager.getOWLDataFactory().getRDFSLabel().getIRI();
        DisplayNameSetting englishLabel = new DisplayNameSetting(label, "en");
        return List.of(
                ClassHierarchyView::new,
                ObjectPropertyHierarchyView::new,
                DataPropertyHierarchyView::new,
                AnnotationPropertyHierarchyView::new,
                () -> new DisplayNameView(List.of(englishLabel)));
    }

    private static void requireViews(ProjectRuntime runtime) {
        runtime.view(ClassHierarchyView.class);
        runtime.view(ObjectPropertyHierarchyView.class);
        runtime.view(DataPropertyHierarchyView.class);
        runtime.view(AnnotationPropertyHierarchyView.class);
        runtime.view(DisplayNameView.class);
    }

    private static long heapUsed() {
        System.gc();
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long[] timeEdits(Loaded loaded, int classCount) {
        ChangePipeline pipeline = new ChangePipeline(
                loaded.runtime(),
                new ProjectWriteGate(),
                loaded.log(),
                FreshIriMinter.NONE,
                List.of(),
                Clock.systemUTC());
        long[] samples = new long[EDITS];
        for (int index = 0; index < EDITS; index++) {
            ChangeListGenerator<Integer> generator = singleEntityEdit(classCount, index);
            long start = System.nanoTime();
            ChangeApplicationResult<Integer> result = pipeline.apply(UserId.SYSTEM, generator);
            samples[index] = System.nanoTime() - start;
            if (result.revision().isEmpty()) {
                throw new IllegalStateException("edit " + index + " was not effective");
            }
            if ((index + 1) % 100 == 0) {
                log.info("edits {}/{}", index + 1, EDITS);
            }
        }
        return samples;
    }

    private static ChangeListGenerator<Integer> singleEntityEdit(int classCount, int index) {
        return context -> {
            OWLDataFactory data = context.ontologies().dataFactory();
            IRI iri = IRI.create("http://example.org/wp-test/synthetic#C" + (index % classCount));
            OWLAxiom axiom = data.getOWLAnnotationAssertionAxiom(
                    data.getRDFSLabel(), iri, data.getOWLLiteral("bench-edit-" + index, "en"));
            return new GeneratedChanges<>(List.of(new AddAxiom(ROOT, axiom)), index);
        };
    }

    private static LineReport measureLines() {
        log.info("encode {} change lines", LINE_COUNT);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        List<OntologyChange> changes = lineChanges(data);
        OfnLineChangeCodec codec = new OfnLineChangeCodec(data);
        byte[] payload = codec.encode(changes);
        changes = null;
        System.gc();
        log.info("decode {} change lines", LINE_COUNT);
        long start = System.nanoTime();
        List<OntologyChange> decoded = codec.decode(payload);
        long elapsed = System.nanoTime() - start;
        return new LineReport(decoded.size(), elapsed);
    }

    private static List<OntologyChange> lineChanges(OWLDataFactory data) {
        List<OntologyChange> changes = new ArrayList<>(LINE_COUNT);
        for (int index = 0; index < LINE_COUNT; index++) {
            IRI iri = IRI.create("http://example.org/wp-test/lines#C" + index);
            changes.add(new AddAxiom(ROOT, data.getOWLDeclarationAxiom(data.getOWLClass(iri))));
        }
        return changes;
    }

    private static long percentile(long[] samples, double fraction) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static Path writeReport(List<ScaleReport> scales, LineReport lines) throws IOException {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Path report = repositoryRoot().resolve("docs").resolve("perf").resolve("kernel-benchmark-" + today + ".md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, reportText(today, scales, lines));
        return report;
    }

    private static String reportText(LocalDate today, List<ScaleReport> scales, LineReport lines) {
        StringBuilder text = new StringBuilder();
        text.append("# 内核性能基准（").append(today).append("）\n\n");
        text.append("测量 OWL API 自带索引与 `ofn-zip/1`、`ofn-lines/1` 是否达到 ADR-005 / ADR-011 的初始门禁。");
        text.append("没有改内核实现。\n\n");
        text.append(machineSection());
        text.append(resultsTable(scales, lines));
        text.append(gateSection(scales, lines));
        text.append(methodSection(scales));
        return text.toString();
    }

    private static String machineSection() {
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
        text.append("| 堆上限 Runtime.maxMemory | ").append(bytes(heap.getMax())).append(" |\n\n");
        return text.toString();
    }

    private static String resultsTable(List<ScaleReport> scales, LineReport lines) {
        StringBuilder text = new StringBuilder();
        text.append("## 结果\n\n");
        text.append("| 规模 | 场景 | 测量值 | 门禁 | 判定 |\n");
        text.append("| --- | --- | --- | --- | --- |\n");
        for (ScaleReport scale : scales) {
            appendScaleRows(text, scale);
        }
        double lineRate = perSecond(lines.decodedLines(), lines.decodeNanos());
        text.append("| ")
                .append(grouped(lines.decodedLines()))
                .append(" 行 | ofn-lines/1 解码 | ")
                .append(rate(lineRate))
                .append(" 行/秒（")
                .append(seconds(lines.decodeNanos()))
                .append("） | ≥ 20,000 行/秒 | ")
                .append(mark(lineRate >= LINE_MIN_PER_SECOND))
                .append(" |\n\n");
        return text.toString();
    }

    private static void appendScaleRows(StringBuilder text, ScaleReport scale) {
        String size = scale.target() + "（" + grouped(scale.axiomCount()) + " 公理）";
        row(text, size, "快照编码", seconds(scale.encodeNanos()), "无", "无门禁");
        row(
                text,
                size,
                "快照解码",
                seconds(scale.decodeNanos()),
                "≤ 30 秒",
                mark(scale.decodeNanos() <= DECODE_LIMIT_NANOS));
        double replayRate = perSecond(REPLAY_CHANGES, scale.replayNanos());
        row(
                text,
                size,
                "重放吞吐",
                rate(replayRate) + " 条变更/秒（" + seconds(scale.replayNanos()) + "）",
                "≥ 5,000 条变更/秒",
                mark(replayRate >= REPLAY_MIN_PER_SECOND));
        row(text, size, "单次编辑 p50", millis(scale.editP50Nanos()), "无", "无门禁");
        row(
                text,
                size,
                "单次编辑 p95",
                millis(scale.editP95Nanos()),
                "≤ 100 毫秒",
                mark(scale.editP95Nanos() <= EDIT_P95_LIMIT_NANOS));
        row(text, size, "加载后堆占用", bytes(scale.heapBytes()), "≤ 2.5 GiB", mark(scale.heapBytes() <= HEAP_LIMIT_BYTES));
    }

    private static String gateSection(List<ScaleReport> scales, LineReport lines) {
        ScaleReport large = scales.getLast();
        boolean pass = officialGatesPass(large, lines);
        StringBuilder text = new StringBuilder();
        text.append("## 门禁结论\n\n");
        text.append("初始门禁只看约 50 万公理规模，加上逐行解码。约 10 万规模用同一数值对照，不单独作为门槛。\n\n");
        text.append("| 项 | 测量值 | 判定 |\n");
        text.append("| --- | --- | --- |\n");
        text.append("| 快照解码 | ")
                .append(seconds(large.decodeNanos()))
                .append(" | ")
                .append(mark(large.decodeNanos() <= DECODE_LIMIT_NANOS))
                .append(" |\n");
        text.append("| 加载后堆占用 | ")
                .append(bytes(large.heapBytes()))
                .append(" | ")
                .append(mark(large.heapBytes() <= HEAP_LIMIT_BYTES))
                .append(" |\n");
        text.append("| 单次编辑 p95 | ")
                .append(millis(large.editP95Nanos()))
                .append(" | ")
                .append(mark(large.editP95Nanos() <= EDIT_P95_LIMIT_NANOS))
                .append(" |\n");
        double replayRate = perSecond(REPLAY_CHANGES, large.replayNanos());
        text.append("| 重放 | ")
                .append(rate(replayRate))
                .append(" 条变更/秒 | ")
                .append(mark(replayRate >= REPLAY_MIN_PER_SECOND))
                .append(" |\n");
        double lineRate = perSecond(lines.decodedLines(), lines.decodeNanos());
        text.append("| 逐行解码 | ")
                .append(rate(lineRate))
                .append(" 行/秒 | ")
                .append(mark(lineRate >= LINE_MIN_PER_SECOND))
                .append(" |\n\n");
        text.append(pass ? "结论：全部达标。\n\n" : "结论：存在未达标项。\n\n");
        return text.toString();
    }

    private static boolean officialGatesPass(ScaleReport large, LineReport lines) {
        return large.decodeNanos() <= DECODE_LIMIT_NANOS
                && large.heapBytes() <= HEAP_LIMIT_BYTES
                && large.editP95Nanos() <= EDIT_P95_LIMIT_NANOS
                && perSecond(REPLAY_CHANGES, large.replayNanos()) >= REPLAY_MIN_PER_SECOND
                && perSecond(lines.decodedLines(), lines.decodeNanos()) >= LINE_MIN_PER_SECOND;
    }

    private static String methodSection(List<ScaleReport> scales) {
        StringBuilder text = new StringBuilder();
        text.append("## 测法\n\n");
        text.append("- 生成器：`SyntheticOntologyGenerator`，分支系数 ")
                .append(BRANCHING)
                .append("，每类 1 条 `rdfs:label`（en），种子 ")
                .append(SEED)
                .append("。类数 ");
        text.append(scales.get(0).classCount())
                .append(" 与 ")
                .append(scales.get(1).classCount());
        text.append("，公理数分别是 `3 × 类数 − 1`。\n");
        text.append("- 快照：`OfnZipSnapshotCodec`（ofn-zip/1）对导入后的项目本体编码、再解码。计时不含生成。\n");
        text.append("- 重放：快照解码之后，向 `InMemoryRevisionLog` 写入 ")
                .append(REVISIONS)
                .append(" 个修订，每个 ")
                .append(CHANGES_PER_REVISION)
                .append(" 条新类声明。计时只包含 `read` 与 `MutableProjectOntologies.apply`，");
        text.append("与 `ProjectRuntimeLoader` 的重放循环相同，不含快照解码，也不含派生视图的一次重建。\n");
        text.append("- 编辑：`ProjectRuntimeLoader` 从修订 0 的快照加载，注册类、对象属性、数据属性、注解属性层级和显示名称");
        text.append("（`rdfs:label` / en）。`ChangePipeline` 使用内存日志，连续 ")
                .append(EDITS)
                .append(" 次各新增一条语言为 en 的 `rdfs:label`。");
        text.append("p50 / p95 用最近秩：排序后第 `ceil(p × n)` 个样本。\n");
        text.append("- 堆：丢掉生成器本体、编码字节和重放副本后，对留下的 `ProjectRuntime` 调用两次 `System.gc()`，");
        text.append("再读 `MemoryMXBean` 的堆 `used`。2.5 GiB = 2.5 × 1024³ 字节。这是进程堆占用，不是单一对象的保留集。\n");
        text.append("- 逐行解码：`OfnLineChangeCodec` 先编码 ")
                .append(grouped(LINE_COUNT))
                .append(" 条类声明，计时只包含 `decode`。\n");
        return text.toString();
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

    private static double perSecond(long count, long nanos) {
        return count / (nanos / 1_000_000_000d);
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f 秒", nanos / 1_000_000_000d);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f 毫秒", nanos / 1_000_000d);
    }

    private static String rate(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String bytes(long value) {
        return String.format(Locale.ROOT, "%.2f GiB（%d 字节）", value / 1024d / 1024d / 1024d, value);
    }

    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private record ScaleReport(
            String target,
            int classCount,
            long axiomCount,
            long encodeNanos,
            long decodeNanos,
            long replayNanos,
            long editP50Nanos,
            long editP95Nanos,
            long heapBytes) {}

    private record LineReport(int decodedLines, long decodeNanos) {}

    private record Loaded(ProjectRuntime runtime, InMemoryRevisionLog log) {}
}
