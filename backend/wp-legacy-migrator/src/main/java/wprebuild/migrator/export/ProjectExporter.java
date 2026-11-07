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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import wprebuild.migrator.MigratorInfo;
import wprebuild.migrator.legacy.LegacyDataDir;
import wprebuild.migrator.legacy.LegacyProjectLocation;
import wprebuild.migrator.legacy.LegacyRevision;
import wprebuild.migrator.legacy.LegacyRevisionReader;

/**
 * Writes {@code projects/<projectId>/manifest.json}, {@code revisions.jsonl}, and {@code SHA256SUMS}.
 * {@code details} and the other Mongo fields are left for P2-04. {@code head.ofn-lines.gz} is left
 * for P2-05. Change lines are JSON strings with no ADR-011 line escaping.
 */
public final class ProjectExporter {

    static final int FORMAT_VERSION = 1;

    private static final String MANIFEST = "manifest.json";
    private static final String REVISIONS = "revisions.jsonl";
    private static final String CHECKSUMS = "SHA256SUMS";
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
        exportSelected(dataDirectory, outputDirectory, null);
    }

    public void export(Path dataDirectory, Path outputDirectory, String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is blank");
        }
        exportSelected(dataDirectory, outputDirectory, projectId);
    }

    private void exportSelected(Path dataDirectory, Path outputDirectory, String projectId) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        List<LegacyProjectLocation> projects = select(new LegacyDataDir(dataDirectory).projects(), projectId);
        String legacyDataDir = dataDirectory.toAbsolutePath().normalize().toString();
        String exportedAt = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
        List<PackageDraft> drafts = new ArrayList<>();
        for (LegacyProjectLocation project : projects) {
            drafts.add(render(project, legacyDataDir, exportedAt));
        }
        for (PackageDraft draft : drafts) {
            write(outputDirectory, draft);
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
        List<LegacyRevision> revisions = readRevisions(project);
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
        return new PackageDraft(
                project.projectId(),
                legacyDataDir,
                exportedAt,
                lines,
                assigner.ontologies(),
                revisions.size(),
                headRevision,
                changeCount);
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

    private void write(Path outputDirectory, PackageDraft draft) {
        Path projectDir = outputDirectory.resolve("projects").resolve(draft.projectId());
        try {
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve(MANIFEST), manifestJson(draft), StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve(REVISIONS), revisionsJson(draft.revisions()), StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve(CHECKSUMS), checksums(projectDir), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write migration package: " + projectDir, exception);
        }
    }

    private static String manifestJson(PackageDraft draft) throws JsonProcessingException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("projectId", draft.projectId());
        manifest.put("exportedAt", draft.exportedAt());
        manifest.put("source", source(draft.legacyDataDir()));
        manifest.put(
                "ontologies",
                draft.ontologies().stream().map(ProjectExporter::ontology).toList());
        manifest.put("stats", stats(draft));
        String pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        if (pretty.endsWith("\n")) {
            return pretty;
        }
        return pretty + "\n";
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
     * can check the directory. Only the files this export wrote are listed; P2-05 adds the head file.
     */
    private static String checksums(Path projectDir) throws IOException {
        StringBuilder sums = new StringBuilder();
        for (String name : List.of(MANIFEST, REVISIONS)) {
            sums.append(sha256(projectDir.resolve(name)))
                    .append("  ")
                    .append(name)
                    .append('\n');
        }
        return sums.toString();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private record PackageDraft(
            String projectId,
            String legacyDataDir,
            String exportedAt,
            List<Map<String, Object>> revisions,
            List<OntologyKeyAssigner.Slot> ontologies,
            long revisionCount,
            long headRevision,
            long changeCount) {}
}
