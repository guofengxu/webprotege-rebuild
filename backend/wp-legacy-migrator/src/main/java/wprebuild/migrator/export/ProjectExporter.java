package wprebuild.migrator.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.model.OWLOntologyID;
import wprebuild.migrator.MigratorInfo;
import wprebuild.migrator.legacy.LegacyDataDir;
import wprebuild.migrator.legacy.LegacyProjectLocation;
import wprebuild.migrator.legacy.LegacyReadException;
import wprebuild.migrator.legacy.LegacyRevision;
import wprebuild.migrator.legacy.LegacyRevisionReader;
import wprebuild.migrator.mongo.MongoMetadata;

/**
 * Writes one migration package: {@code users.json}, per-project manifest, revisions, head baseline,
 * checksums, and {@code migration-report.json}.
 *
 * <p>Change lines in {@code revisions.jsonl} are JSON strings with no ADR-011 line escaping.
 * {@code head.ofn-lines.gz} is the head state under OWL API 4: one escaped {@code +} line per axiom,
 * ontology annotation, or import, sorted as text.
 */
public final class ProjectExporter {

    static final int FORMAT_VERSION = 1;

    private static final String USERS = "users.json";
    private static final String REPORT = "migration-report.json";
    private static final String VERSION_RESOURCE = "/wprebuild/migrator/version.properties";
    private static final String TOOL_VERSION = readToolVersion();
    private static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);

    private final Clock clock;
    private final LegacyRevisionReader reader;

    public ProjectExporter(Clock clock) {
        this(clock, new LegacyRevisionReader());
    }

    ProjectExporter(Clock clock, LegacyRevisionReader reader) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public void export(Path dataDirectory, Path outputDirectory) {
        export(dataDirectory, outputDirectory, MongoMetadata.empty());
    }

    public void export(Path dataDirectory, Path outputDirectory, String projectId) {
        export(dataDirectory, outputDirectory, projectId, MongoMetadata.empty());
    }

    public void export(Path dataDirectory, Path outputDirectory, MongoMetadata metadata) {
        exportSelected(dataDirectory, outputDirectory, null, Objects.requireNonNull(metadata, "metadata"));
    }

    public void export(Path dataDirectory, Path outputDirectory, String projectId, MongoMetadata metadata) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(metadata, "metadata");
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is blank");
        }
        exportSelected(dataDirectory, outputDirectory, projectId, metadata);
    }

    private void exportSelected(Path dataDirectory, Path outputDirectory, String projectId, MongoMetadata metadata) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        List<LegacyProjectLocation> projects = select(new LegacyDataDir(dataDirectory).projects(), projectId);
        String legacyDataDir = dataDirectory.toAbsolutePath().normalize().toString();
        String exportedAt = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
        List<ExportOutcome> outcomes = new ArrayList<>();
        List<PackageDraft> drafts = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (LegacyProjectLocation project : projects) {
            try {
                PackageDraft draft = render(project, legacyDataDir, exportedAt);
                drafts.add(draft);
                outcomes.add(draft);
            } catch (IllegalArgumentException | LegacyReadException exception) {
                outcomes.add(new ProjectFailure(project.projectId(), exception.getMessage()));
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        UncheckedIOException writeError = null;
        try {
            for (PackageDraft draft : drafts) {
                write(outputDirectory, draft, metadata);
            }
        } catch (UncheckedIOException exception) {
            writeError = exception;
        }
        try {
            writePackageFiles(outputDirectory, metadata, outcomes);
        } catch (UncheckedIOException exception) {
            if (writeError != null) {
                exception.addSuppressed(writeError);
            }
            throw exception;
        }
        if (writeError != null) {
            if (firstFailure != null) {
                writeError.addSuppressed(firstFailure);
            }
            throw writeError;
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static List<LegacyProjectLocation> select(List<LegacyProjectLocation> projects, String projectId) {
        if (projectId == null) {
            return projects;
        }
        List<LegacyProjectLocation> selected = projects.stream()
                .filter(project -> project.projectId().equals(projectId))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
        return selected;
    }

    private PackageDraft render(LegacyProjectLocation project, String legacyDataDir, String exportedAt) {
        requireSafeProjectId(project.projectId());
        List<LegacyRevision> revisions = shareAnonymousOntology(readRevisions(project));
        OntologyKeyAssigner assigner = new OntologyKeyAssigner();
        OfnLineRenderer renderer = new OfnLineRenderer();
        List<Map<String, Object>> lines = new ArrayList<>();
        long changeCount = 0;
        for (LegacyRevision revision : revisions) {
            List<String> changes = renderChanges(project.projectId(), revision, assigner, renderer);
            changeCount += changes.size();
            lines.add(revisionLine(revision, changes));
        }
        long headRevision =
                revisions.isEmpty() ? 0 : revisions.get(revisions.size() - 1).revision();
        HeadBaseline.Result head = headOf(project.projectId(), revisions, assigner);
        return new PackageDraft(
                project.projectId(),
                legacyDataDir,
                exportedAt,
                lines,
                assigner.ontologies(),
                revisions.size(),
                headRevision,
                changeCount,
                head.gzip(),
                head.axiomCounts());
    }

    private static HeadBaseline.Result headOf(
            String projectId, List<LegacyRevision> revisions, OntologyKeyAssigner assigner) {
        try {
            return HeadBaseline.capture(revisions, assigner);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("project %s: %s".formatted(projectId, detail(exception)), exception);
        }
    }

    /**
     * BinaryOWL stores every anonymous ontology as the same marker, so each read mints a new id.
     * The legacy editor replays all of those ids as one ontology. Do that before keys and the head
     * baseline, or a remove in a later revision misses the axiom added earlier.
     */
    private static List<LegacyRevision> shareAnonymousOntology(List<LegacyRevision> revisions) {
        OWLOntologyID shared = null;
        List<LegacyRevision> rewritten = new ArrayList<>(revisions.size());
        for (LegacyRevision revision : revisions) {
            List<OWLOntologyChangeRecord> records =
                    new ArrayList<>(revision.records().size());
            boolean changed = false;
            for (OWLOntologyChangeRecord record : revision.records()) {
                OWLOntologyID ontologyId = record.getOntologyID();
                if (ontologyId.isAnonymous()) {
                    if (shared == null) {
                        shared = ontologyId;
                    } else if (!shared.equals(ontologyId)) {
                        record = new OWLOntologyChangeRecord(shared, record.getData());
                        changed = true;
                    }
                }
                records.add(record);
            }
            rewritten.add(
                    changed
                            ? new LegacyRevision(
                                    revision.revision(),
                                    revision.username(),
                                    revision.timestamp(),
                                    revision.description(),
                                    records)
                            : revision);
        }
        return List.copyOf(rewritten);
    }

    private List<LegacyRevision> readRevisions(LegacyProjectLocation project) {
        if (Files.notExists(project.changeDataBinary())) {
            return List.of();
        }
        List<LegacyRevision> revisions = new ArrayList<>();
        reader.read(project.changeDataBinary(), revisions::add);
        requireContiguous(project.projectId(), revisions);
        return List.copyOf(revisions);
    }

    private static List<String> renderChanges(
            String projectId, LegacyRevision revision, OntologyKeyAssigner assigner, OfnLineRenderer renderer) {
        List<OWLOntologyChangeRecord> records = revision.records();
        List<String> changes = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            OWLOntologyChangeRecord record = records.get(index);
            String key = assigner.keyFor(record.getOntologyID());
            try {
                changes.add(renderer.render(record, key));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "project %s revision %d changeIndex %d: %s"
                                .formatted(projectId, revision.revision(), index, detail(exception)),
                        exception);
            }
        }
        return List.copyOf(changes);
    }

    private static void requireContiguous(String projectId, List<LegacyRevision> revisions) {
        for (int index = 0; index < revisions.size(); index++) {
            long expected = index + 1L;
            long actual = revisions.get(index).revision();
            if (actual != expected) {
                throw new IllegalArgumentException(
                        "project %s revision numbers must start at 1 and increase by 1, but position %d is %d"
                                .formatted(projectId, index + 1, actual));
            }
        }
    }

    private static void requireSafeProjectId(String projectId) {
        if (projectId.indexOf('/') >= 0
                || projectId.indexOf('\\') >= 0
                || projectId.indexOf('\0') >= 0
                || ".".equals(projectId)
                || "..".equals(projectId)) {
            throw new IllegalArgumentException("project id is not a single directory name: " + projectId);
        }
    }

    private void write(Path outputDirectory, PackageDraft draft, MongoMetadata metadata) {
        Path projectDir = outputDirectory.resolve("projects").resolve(draft.projectId());
        try {
            Files.createDirectories(projectDir);
            Files.writeString(
                    projectDir.resolve(PackageFiles.MANIFEST), manifestJson(draft, metadata), StandardCharsets.UTF_8);
            Files.writeString(
                    projectDir.resolve(PackageFiles.REVISIONS),
                    revisionsJson(draft.revisions()),
                    StandardCharsets.UTF_8);
            Files.write(projectDir.resolve(PackageFiles.HEAD), draft.headGzip());
            Files.writeString(
                    projectDir.resolve(PackageFiles.CHECKSUMS), checksums(projectDir), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write migration package: " + projectDir, exception);
        }
    }

    private void writePackageFiles(Path outputDirectory, MongoMetadata metadata, List<ExportOutcome> outcomes) {
        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(outputDirectory.resolve(USERS), pretty(metadata.users()), StandardCharsets.UTF_8);
            Files.writeString(
                    outputDirectory.resolve(REPORT), pretty(report(metadata, outcomes)), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write migration package: " + outputDirectory, exception);
        }
    }

    private static String manifestJson(PackageDraft draft, MongoMetadata metadata) throws JsonProcessingException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("projectId", draft.projectId());
        manifest.put("exportedAt", draft.exportedAt());
        manifest.put("source", source(draft.legacyDataDir()));
        manifest.put("details", metadata.details(draft.projectId()));
        manifest.put(
                "ontologies",
                draft.ontologies().stream().map(ProjectExporter::ontology).toList());
        manifest.put("stats", stats(draft));
        manifest.put("prefixes", metadata.prefixes(draft.projectId()));
        manifest.put("entityCrudKitSettings", metadata.entityCrudKitSettings(draft.projectId()));
        manifest.put("displayNameSettings", metadata.displayNameSettings(draft.projectId()));
        manifest.put("roleAssignments", metadata.roleAssignments(draft.projectId()));
        return pretty(manifest);
    }

    private static Map<String, Object> report(MongoMetadata metadata, List<ExportOutcome> outcomes) {
        List<String> warnings = new ArrayList<>(metadata.warnings());
        List<Map<String, Object>> projects = new ArrayList<>();
        for (ExportOutcome outcome : outcomes) {
            projects.add(projectReport(outcome));
            if (metadata.loaded() && !metadata.hasDetails(outcome.projectId())) {
                warnings.add("no ProjectDetails document for project " + outcome.projectId());
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("projects", List.copyOf(projects));
        report.put("warnings", List.copyOf(warnings));
        return report;
    }

    private static Map<String, Object> projectReport(ExportOutcome outcome) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("projectId", outcome.projectId());
        if (outcome instanceof ProjectFailure failure) {
            project.put("status", "failure");
            project.put("reason", failure.reason());
            return project;
        }
        PackageDraft draft = (PackageDraft) outcome;
        project.put("status", "success");
        project.put("revisionCount", draft.revisionCount());
        project.put("changeCount", draft.changeCount());
        project.put("axiomCounts", draft.axiomCounts());
        return project;
    }

    private static String pretty(Object value) throws JsonProcessingException {
        String body = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        if (body.endsWith("\n")) {
            return body;
        }
        return body + "\n";
    }

    private static Map<String, Object> source(String legacyDataDir) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("tool", MigratorInfo.NAME);
        source.put("toolVersion", TOOL_VERSION);
        source.put("legacyDataDir", legacyDataDir);
        return source;
    }

    private static Map<String, Object> ontology(OntologyKeyAssigner.Slot slot) {
        Map<String, Object> ontology = new LinkedHashMap<>();
        ontology.put("key", slot.key());
        ontology.put("ontologyIri", slot.ontologyIri());
        ontology.put("versionIri", slot.versionIri());
        return ontology;
    }

    private static Map<String, Object> stats(PackageDraft draft) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("revisionCount", draft.revisionCount());
        stats.put("headRevision", draft.headRevision());
        stats.put("changeCount", draft.changeCount());
        return stats;
    }

    private static Map<String, Object> revisionLine(LegacyRevision revision, List<String> changes) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("rev", revision.revision());
        line.put("user", revision.username());
        line.put("ts", revision.timestamp());
        line.put("desc", revision.description());
        line.put("changes", changes);
        return line;
    }

    private static String revisionsJson(List<Map<String, Object>> revisions) throws JsonProcessingException {
        if (revisions.isEmpty()) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> revision : revisions) {
            body.append(JSON.writeValueAsString(revision)).append('\n');
        }
        return body.toString();
    }

    /**
     * GNU {@code sha256sum} text mode ({@code hash} + two spaces + file name) so {@code sha256sum -c}
     * can check the directory.
     */
    private static String checksums(Path projectDir) throws IOException {
        StringBuilder sums = new StringBuilder();
        for (String name : PackageFiles.CHECKED) {
            sums.append(PackageFiles.sha256(projectDir.resolve(name)))
                    .append("  ")
                    .append(name)
                    .append('\n');
        }
        return sums.toString();
    }

    private static String readToolVersion() {
        try (InputStream in = ProjectExporter.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + VERSION_RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.contains("${")) {
                throw new IllegalStateException("unfiltered version: " + version);
            }
            return version;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read migrator version", exception);
        }
    }

    private static String detail(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private sealed interface ExportOutcome {
        String projectId();
    }

    private record ProjectFailure(String projectId, String reason) implements ExportOutcome {}

    private record PackageDraft(
            String projectId,
            String legacyDataDir,
            String exportedAt,
            List<Map<String, Object>> revisions,
            List<OntologyKeyAssigner.Slot> ontologies,
            long revisionCount,
            long headRevision,
            long changeCount,
            byte[] headGzip,
            Map<String, Map<String, Integer>> axiomCounts)
            implements ExportOutcome {}
}
