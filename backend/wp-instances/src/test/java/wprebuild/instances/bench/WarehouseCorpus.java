package wprebuild.instances.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Random;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-seed warehouse corpus for the instance-store PoC benchmark (P6-32). Uses PostgreSQL {@code
 * COPY} so full-scale load stays practical.
 */
public final class WarehouseCorpus {

    private static final Logger log = LoggerFactory.getLogger(WarehouseCorpus.class);

    /** Corpus RNG seed — kept stable so sample IRIs stay reproducible. */
    public static final long SEED = 42L;

    public static final String NS = "http://wp.local/wh#";
    public static final String BASE = "http://wp.local/wh/";

    public static final String CLASS_SLOT = NS + "Slot";
    public static final String CLASS_SENSOR = NS + "Sensor";
    public static final String CLASS_STACKER = NS + "Stacker";
    public static final String CLASS_AGV = NS + "Agv";
    public static final String CLASS_BATCH = NS + "Batch";
    public static final String CLASS_TRAY = NS + "Tray";
    public static final String CLASS_TASK = NS + "Task";
    public static final String CLASS_ENV_EVENT = NS + "EnvEvent";

    public static final String PROP_STORED_AT = NS + "storedAt";
    public static final String PROP_CONTAINS = NS + "contains";
    public static final String PROP_EXECUTED_BY = NS + "executedBy";
    public static final String PROP_MOVES = NS + "moves";
    public static final String PROP_MONITORS = NS + "monitors";
    public static final String PROP_OBSERVED_BY = NS + "observedBy";

    public static final Instant DAY0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final String SOURCE = "wcs";
    private static final long ONTOLOGY_REV = 1L;
    private static final int COPY_FLUSH_BYTES = 4 * 1024 * 1024;

    private WarehouseCorpus() {}

    public static Scale full() {
        return new Scale(18_000, 5, 8, 20_000, 60_000, 30_000, 30, 1_000_000, false);
    }

    /**
     * Roughly 1/100 of full volumes (equipment floored at 1). {@code tasksPerDay} is kept small so
     * {@code TraceQueries.follow} for Q3 stays within the two-minute smoke budget (N+1 hops).
     */
    public static Scale smoke() {
        return new Scale(180, 1, 1, 200, 600, 20, 30, 10_000, true);
    }

    public static Loaded load(DataSource dataSource, Scale scale) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(scale, "scale");
        Instant corpusEnd = DAY0.plus(scale.days(), ChronoUnit.DAYS);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("truncate links, instances");
            }

            CopyManager copy = connection.unwrap(PGConnection.class).getCopyAPI();
            long instanceRows = copyInstances(copy, scale);
            long linkRows = copyLinks(copy, scale, corpusEnd);
            connection.commit();

            SampleIris samples = SampleIris.pick(scale, new Random(SEED + 1));
            log.info(
                    "corpus loaded smoke={} instances={} links={} samples={}",
                    scale.smoke(),
                    instanceRows,
                    linkRows,
                    samples);
            return new Loaded(scale, corpusEnd, samples, instanceRows, linkRows);
        }
    }

    private static long copyInstances(CopyManager copy, Scale scale) throws SQLException {
        Random random = new Random(SEED);
        CopyBuffer buffer = new CopyBuffer(copy, """
                copy instances (iri, class_iri, attributes, valid_from, valid_to, source, ontology_rev)
                from stdin with (format text, null '\\N')
                """);
        Instant t0 = DAY0;
        int sensors = scale.sensors();

        for (int i = 0; i < scale.slots(); i++) {
            buffer.instance(slotIri(i), CLASS_SLOT, "{}", t0, null);
        }
        for (int i = 0; i < sensors; i++) {
            buffer.instance(sensorIri(i), CLASS_SENSOR, "{}", t0, null);
        }
        for (int i = 0; i < scale.stackers(); i++) {
            buffer.instance(stackerIri(i), CLASS_STACKER, "{}", t0, null);
        }
        for (int i = 0; i < scale.agvs(); i++) {
            buffer.instance(agvIri(i), CLASS_AGV, "{}", t0, null);
        }
        for (int i = 0; i < scale.batches(); i++) {
            buffer.instance(batchIri(i), CLASS_BATCH, "{\"lot\":\"" + i + "\"}", t0, null);
        }
        for (int i = 0; i < scale.trays(); i++) {
            buffer.instance(trayIri(i), CLASS_TRAY, "{\"status\":\"stored\"}", t0, null);
        }

        int equipment = scale.stackers() + scale.agvs();
        long taskIndex = 0;
        for (int day = 0; day < scale.days(); day++) {
            Instant dayStart = DAY0.plus(day, ChronoUnit.DAYS);
            for (int n = 0; n < scale.tasksPerDay(); n++) {
                int equip = (int) (taskIndex % equipment);
                String attrs = "{\"day\":" + day + ",\"equip\":" + equip + "}";
                buffer.instance(taskIri(day, n), CLASS_TASK, attrs, dayStart, null);
                taskIndex++;
            }
        }

        for (int i = 0; i < scale.events(); i++) {
            int sensor = random.nextInt(sensors);
            long offsetSeconds = Math.floorMod(random.nextLong(), scale.days() * 86_400L);
            Instant at = DAY0.plusSeconds(offsetSeconds);
            boolean excursion = random.nextDouble() < 0.01d;
            // Force a few early excursions onto sensor 0 so Q4/Q5 have non-empty seeds.
            if (i < Math.min(10, scale.events())) {
                sensor = 0;
                at = DAY0.plus(1, ChronoUnit.HOURS).plusSeconds(i * 60L);
                excursion = true;
            }
            String attrs = "{\"observedAt\":\"" + at + "\",\"excursion\":" + excursion + ",\"sensor\":" + sensor + "}";
            buffer.instance(eventIri(i), CLASS_ENV_EVENT, attrs, at, null);
        }
        return buffer.finish();
    }

    private static long copyLinks(CopyManager copy, Scale scale, Instant corpusEnd) throws SQLException {
        // Same seed as instances so event sensor / time / excursion draws line up with observedBy.
        Random random = new Random(SEED);
        CopyBuffer buffer = new CopyBuffer(copy, """
                copy links (subject_iri, property_iri, object_iri, valid_from, valid_to)
                from stdin with (format text, null '\\N')
                """);
        Instant t0 = DAY0;
        int sensors = scale.sensors();

        for (int i = 0; i < sensors; i++) {
            buffer.link(sensorIri(i), PROP_MONITORS, slotIri(i * 30), t0, null);
        }

        for (int tray = 0; tray < scale.trays(); tray++) {
            int batch = tray % scale.batches();
            buffer.link(batchIri(batch), PROP_CONTAINS, trayIri(tray), t0, null);
        }

        // storedAt history: initial placement + exactly 3 moves (card average).
        Random moveRandom = new Random(SEED + 7);
        for (int tray = 0; tray < scale.trays(); tray++) {
            int slot = Math.floorMod(tray * 17, scale.slots());
            Instant from = t0;
            for (int move = 0; move < 3; move++) {
                long daySpan = (long) scale.days() * 86_400L;
                long moveAt = 1 + Math.floorMod(moveRandom.nextLong(), daySpan - 1);
                Instant to = DAY0.plusSeconds(Math.min(moveAt + (long) move * 86_400L, daySpan - 1));
                if (!to.isAfter(from)) {
                    to = from.plusSeconds(1);
                }
                if (!to.isBefore(corpusEnd)) {
                    to = corpusEnd.minusSeconds(1);
                }
                if (!to.isAfter(from)) {
                    buffer.link(trayIri(tray), PROP_STORED_AT, slotIri(slot), from, null);
                    from = corpusEnd;
                    break;
                }
                buffer.link(trayIri(tray), PROP_STORED_AT, slotIri(slot), from, to);
                from = to;
                slot = Math.floorMod(slot + 31 + moveRandom.nextInt(97), scale.slots());
            }
            if (from.isBefore(corpusEnd)) {
                buffer.link(trayIri(tray), PROP_STORED_AT, slotIri(slot), from, null);
            }
        }

        int equipment = scale.stackers() + scale.agvs();
        long taskIndex = 0;
        for (int day = 0; day < scale.days(); day++) {
            Instant dayStart = DAY0.plus(day, ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            for (int n = 0; n < scale.tasksPerDay(); n++) {
                int equip = (int) (taskIndex % equipment);
                String equipIri = equip < scale.stackers() ? stackerIri(equip) : agvIri(equip - scale.stackers());
                int tray = (int) (taskIndex % scale.trays());
                String task = taskIri(day, n);
                buffer.link(task, PROP_EXECUTED_BY, equipIri, dayStart, dayEnd);
                buffer.link(task, PROP_MOVES, trayIri(tray), dayStart, dayEnd);
                taskIndex++;
            }
        }

        for (int i = 0; i < scale.events(); i++) {
            int sensor = random.nextInt(sensors);
            long offsetSeconds = Math.floorMod(random.nextLong(), scale.days() * 86_400L);
            Instant at = DAY0.plusSeconds(offsetSeconds);
            random.nextDouble(); // excursion draw — must match copyInstances
            if (i < Math.min(10, scale.events())) {
                sensor = 0;
                at = DAY0.plus(1, ChronoUnit.HOURS).plusSeconds(i * 60L);
            }
            buffer.link(eventIri(i), PROP_OBSERVED_BY, sensorIri(sensor), at, null);
        }
        return buffer.finish();
    }

    public static String slotIri(int index) {
        return BASE + "Slot/" + index;
    }

    public static String sensorIri(int index) {
        return BASE + "Sensor/" + index;
    }

    public static String stackerIri(int index) {
        return BASE + "Stacker/" + index;
    }

    public static String agvIri(int index) {
        return BASE + "Agv/" + index;
    }

    public static String batchIri(int index) {
        return BASE + "Batch/" + index;
    }

    public static String trayIri(int index) {
        return BASE + "Tray/" + index;
    }

    public static String taskIri(int day, int index) {
        return BASE + "Task/" + day + "/" + index;
    }

    public static String eventIri(int index) {
        return BASE + "EnvEvent/" + index;
    }

    public record Scale(
            int slots,
            int stackers,
            int agvs,
            int batches,
            int trays,
            int tasksPerDay,
            int days,
            int events,
            boolean smoke) {

        public Scale {
            if (slots < 1
                    || stackers < 1
                    || agvs < 1
                    || batches < 1
                    || trays < 1
                    || tasksPerDay < 1
                    || days < 1
                    || events < 1) {
                throw new IllegalArgumentException("all scale dimensions must be >= 1");
            }
            if (slots < 30) {
                throw new IllegalArgumentException("slots must be >= 30 so at least one sensor fits");
            }
        }

        public int sensors() {
            return slots / 30;
        }

        public long totalTasks() {
            return (long) tasksPerDay * days;
        }
    }

    public record SampleIris(
            String slotIri,
            String batchIri,
            String stackerIri,
            int taskDay,
            String deepBatchIri,
            String excursionEventIri) {

        static SampleIris pick(Scale scale, Random random) {
            int slot = random.nextInt(scale.slots());
            int batch = random.nextInt(scale.batches());
            int stacker = random.nextInt(scale.stackers());
            int day = random.nextInt(scale.days());
            // Batch 0 contains tray 0, which starts at slot 0 monitored by sensor 0 (forced excursions).
            int deepBatch = 0;
            int event = 0;
            return new SampleIris(
                    WarehouseCorpus.slotIri(slot),
                    WarehouseCorpus.batchIri(batch),
                    WarehouseCorpus.stackerIri(stacker),
                    day,
                    WarehouseCorpus.batchIri(deepBatch),
                    WarehouseCorpus.eventIri(event));
        }
    }

    public record Loaded(Scale scale, Instant asOf, SampleIris samples, long instanceRows, long linkRows) {}

    private static final class CopyBuffer {
        private final CopyManager copy;
        private final String copySql;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(COPY_FLUSH_BYTES);
        private long rows;

        CopyBuffer(CopyManager copy, String copySql) {
            this.copy = copy;
            this.copySql = copySql;
        }

        void instance(String iri, String classIri, String attrs, Instant validFrom, Instant validTo)
                throws SQLException {
            writeEscaped(iri);
            pending.write('\t');
            writeEscaped(classIri);
            pending.write('\t');
            writeEscaped(attrs);
            pending.write('\t');
            writeEscaped(validFrom.toString());
            pending.write('\t');
            writeNullableInstant(validTo);
            pending.write('\t');
            writeEscaped(SOURCE);
            pending.write('\t');
            writeEscaped(Long.toString(ONTOLOGY_REV));
            pending.write('\n');
            rows++;
            flushIfNeeded();
        }

        void link(String subject, String property, String object, Instant validFrom, Instant validTo)
                throws SQLException {
            writeEscaped(subject);
            pending.write('\t');
            writeEscaped(property);
            pending.write('\t');
            writeEscaped(object);
            pending.write('\t');
            writeEscaped(validFrom.toString());
            pending.write('\t');
            writeNullableInstant(validTo);
            pending.write('\n');
            rows++;
            flushIfNeeded();
        }

        private void writeNullableInstant(Instant validTo) {
            if (validTo == null) {
                pending.write('\\');
                pending.write('N');
            } else {
                writeEscaped(validTo.toString());
            }
        }

        private void flushIfNeeded() throws SQLException {
            if (pending.size() >= COPY_FLUSH_BYTES) {
                flushChunk();
            }
        }

        long finish() throws SQLException {
            flushChunk();
            return rows;
        }

        private void flushChunk() throws SQLException {
            if (pending.size() == 0) {
                return;
            }
            byte[] bytes = pending.toByteArray();
            pending.reset();
            try {
                copy.copyIn(copySql, new ByteArrayInputStream(bytes));
            } catch (java.io.IOException e) {
                throw new SQLException("COPY flush failed", e);
            }
        }

        private void writeEscaped(String value) {
            byte[] raw = value.getBytes(StandardCharsets.UTF_8);
            for (byte b : raw) {
                if (b == '\\' || b == '\t' || b == '\n' || b == '\r') {
                    pending.write('\\');
                }
                pending.write(b);
            }
        }
    }
}
