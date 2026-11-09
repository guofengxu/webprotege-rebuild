package wprebuild.app.migration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnLines;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

/**
 * Reads a migration package and checks IC-1 through IC-6.
 *
 * <p>Change lines are decoded with {@link OfnLines#decodeLine}. Import IRIs are not fetched.
 * Checksum names must be bare file names, and every hashed file must stay inside the package.
 */
@Component
public final class MigrationPackageReader {

    static final String MANIFEST = "manifest.json";

    static final String REVISIONS = "revisions.jsonl";

    static final String HEAD = "head.ofn-lines.gz";

    static final String CHECKSUMS = "SHA256SUMS";

    private static final List<String> CHECKED = List.of(MANIFEST, REVISIONS, HEAD);

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final long MAX_CHECKSUM_BYTES = 1024 * 1024L;

    private static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;

    private static final long MAX_REVISION_BYTES = 1024L * 1024 * 1024;

    /** Logical size. Checked before hashing so a sparse file is not read to the end. */
    private static final long MAX_HASH_BYTES = 1024L * 1024 * 1024;

    private static final int DETAIL_LIMIT = 300;

    private static final JsonMapper JSON = new JsonMapper();

    private final OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();

    public MigrationPackage read(Path packageDirectory) {
        Objects.requireNonNull(packageDirectory, "packageDirectory");
        if (!Files.isDirectory(packageDirectory)) {
            throw failure("package", safeName(packageDirectory), "package directory is missing");
        }
        Path root = realDirectory(packageDirectory);
        Path projectsDir = root.resolve("projects");
        if (!Files.isDirectory(projectsDir) || !contained(root, projectsDir)) {
            throw failure("package", "projects", "projects directory is missing");
        }
        List<MigrationFailure> failures = new ArrayList<>();
        List<MigrationProject> projects = new ArrayList<>();
        for (Path projectDir : directories(projectsDir, failures)) {
            readProject(root, projectDir, failures).ifPresent(projects::add);
        }
        if (!failures.isEmpty()) {
            throw new InvalidMigrationPackageException(failures);
        }
        return new MigrationPackage(projects);
    }

    private Optional<MigrationProject> readProject(Path root, Path projectDir, List<MigrationFailure> failures) {
        String where = "projects/" + projectDir.getFileName();
        if (!contained(root, projectDir)) {
            failures.add(new MigrationFailure("package", where, "path escapes the package"));
            return Optional.empty();
        }
        int before = failures.size();
        checkChecksums(root, projectDir, failures);
        JsonNode manifest = readManifest(root, projectDir, failures);
        if (manifest == null) {
            return Optional.empty();
        }
        checkFormat(projectDir, manifest, failures);
        JsonNode details = manifest.path("details");
        String displayName = text(details, "displayName", projectDir, failures);
        String description = text(details, "description", projectDir, failures);
        String owner = text(details, "owner", projectDir, failures);
        boolean inTrash = flag(details, projectDir, failures);
        Instant createdAt = epoch(details, "createdAt", projectDir, failures);
        String createdBy = text(details, "createdBy", projectDir, failures);
        Instant modifiedAt = epoch(details, "modifiedAt", projectDir, failures);
        String modifiedBy = text(details, "modifiedBy", projectDir, failures);
        List<RegisteredOntology> ontologies = readOntologies(projectDir, manifest, failures);
        RevisionDraft draft = readRevisions(root, projectDir, ontologies, failures);
        if (draft == null) {
            return Optional.empty();
        }
        checkRevisionNumbers(projectDir, manifest, draft, failures);
        checkChangeCount(projectDir, manifest, draft, failures);
        if (failures.size() != before || !draft.complete()) {
            return Optional.empty();
        }
        return Optional.of(new MigrationProject(
                ProjectId.of(projectDir.getFileName().toString()),
                where,
                displayName,
                description,
                owner,
                inTrash,
                createdAt,
                createdBy,
                modifiedAt,
                modifiedBy,
                ontologies,
                draft.revisions(),
                projectDir.resolve(HEAD)));
    }

    private void checkChecksums(Path root, Path projectDir, List<MigrationFailure> failures) {
        Path sums = projectDir.resolve(CHECKSUMS);
        String location = location(projectDir, CHECKSUMS);
        if (!Files.isRegularFile(sums) || !contained(root, sums)) {
            failures.add(new MigrationFailure("IC-1", location, "file is missing"));
            return;
        }
        if (largerThan(sums, MAX_CHECKSUM_BYTES)) {
            failures.add(new MigrationFailure("IC-1", location, "file exceeds size limit"));
            return;
        }
        Map<String, String> listed = new LinkedHashMap<>();
        List<String> lines = readLines(sums);
        for (int index = 0; index < lines.size(); index++) {
            parseChecksumLine(root, projectDir, location, listed, lines.get(index), index + 1, failures);
        }
        for (String name : CHECKED) {
            if (!listed.containsKey(name)) {
                failures.add(new MigrationFailure("IC-1", location(projectDir, name), "missing from SHA256SUMS"));
            }
        }
    }

    private void parseChecksumLine(
            Path root,
            Path projectDir,
            String location,
            Map<String, String> listed,
            String line,
            int lineNumber,
            List<MigrationFailure> failures) {
        if (line.isEmpty()) {
            return;
        }
        int split = line.indexOf("  ");
        if (split != 64 || !SHA256.matcher(line.substring(0, split)).matches()) {
            failures.add(new MigrationFailure("IC-1", location + ":line " + lineNumber, "malformed checksum line"));
            return;
        }
        String hash = line.substring(0, split);
        String name = line.substring(split + 2);
        if (!bareName(name)) {
            failures.add(
                    new MigrationFailure("IC-1", location + ":line " + lineNumber, "entry is not a bare file name"));
            return;
        }
        String previous = listed.put(name, hash);
        if (previous != null && !previous.equals(hash)) {
            failures.add(new MigrationFailure("IC-1", location, "conflicting checksums for " + name));
        }
        Path file = projectDir.resolve(name);
        if (!Files.isRegularFile(file) || !contained(root, file)) {
            failures.add(new MigrationFailure("IC-1", location(projectDir, name), "file is missing"));
            return;
        }
        if (largerThan(file, MAX_HASH_BYTES)) {
            failures.add(new MigrationFailure("IC-1", location(projectDir, name), "file exceeds size limit"));
            return;
        }
        try {
            if (!hash.equals(sha256(file))) {
                failures.add(new MigrationFailure("IC-1", location(projectDir, name), "SHA-256 mismatch"));
            }
        } catch (IOException ex) {
            failures.add(new MigrationFailure("IC-1", location(projectDir, name), "cannot hash file"));
        }
    }

    private JsonNode readManifest(Path root, Path projectDir, List<MigrationFailure> failures) {
        Path file = projectDir.resolve(MANIFEST);
        String location = location(projectDir, MANIFEST);
        if (!Files.isRegularFile(file) || !contained(root, file)) {
            failures.add(new MigrationFailure("IC-2", location, "file is missing"));
            return null;
        }
        if (largerThan(file, MAX_MANIFEST_BYTES)) {
            failures.add(new MigrationFailure("IC-2", location, "file exceeds size limit"));
            return null;
        }
        try {
            JsonNode node = JSON.readTree(file);
            if (!node.isObject()) {
                failures.add(new MigrationFailure("IC-2", location, "manifest must be an object"));
                return null;
            }
            return node;
        } catch (JacksonException ex) {
            failures.add(new MigrationFailure("IC-2", location, "not JSON"));
            return null;
        }
    }

    private static void checkFormat(Path projectDir, JsonNode manifest, List<MigrationFailure> failures) {
        JsonNode version = manifest.get("formatVersion");
        if (version == null
                || !version.isIntegralNumber()
                || !version.canConvertToLong()
                || version.longValue() != 1L) {
            failures.add(new MigrationFailure("IC-2", location(projectDir, MANIFEST), "formatVersion must be 1"));
        }
        JsonNode projectId = manifest.get("projectId");
        String directory = projectDir.getFileName().toString();
        if (projectId == null || !projectId.isString() || !directory.equals(projectId.asString())) {
            failures.add(new MigrationFailure(
                    "IC-2", location(projectDir, MANIFEST), "projectId must match the directory name"));
        }
    }

    private List<RegisteredOntology> readOntologies(
            Path projectDir, JsonNode manifest, List<MigrationFailure> failures) {
        String location = location(projectDir, MANIFEST);
        JsonNode ontologies = manifest.get("ontologies");
        if (ontologies == null || !ontologies.isArray()) {
            failures.add(new MigrationFailure("IC-5", location, "ontologies must be an array"));
            return List.of();
        }
        List<RegisteredOntology> registered = new ArrayList<>();
        Map<OntologyKey, RegisteredOntology> byKey = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode node : ontologies) {
            readOntology(location + ":ontologies[" + index + "]", node, byKey, registered, failures);
            index++;
        }
        return List.copyOf(registered);
    }

    private void readOntology(
            String location,
            JsonNode node,
            Map<OntologyKey, RegisteredOntology> byKey,
            List<RegisteredOntology> registered,
            List<MigrationFailure> failures) {
        if (node == null || !node.isObject()) {
            failures.add(new MigrationFailure("IC-5", location, "ontology entry must be an object"));
            return;
        }
        JsonNode keyNode = node.get("key");
        if (keyNode == null || !keyNode.isString()) {
            failures.add(new MigrationFailure("IC-5", location, "ontology key is missing"));
            return;
        }
        OntologyKey key;
        try {
            key = new OntologyKey(keyNode.asString());
        } catch (IllegalArgumentException ex) {
            failures.add(new MigrationFailure("IC-5", location, detail(ex)));
            return;
        }
        if (byKey.containsKey(key)) {
            failures.add(new MigrationFailure("IC-5", location, "duplicate ontology key " + key.value()));
            return;
        }
        Optional<IRI> ontologyIri = iri(node, "ontologyIri", location, failures);
        Optional<IRI> versionIri = iri(node, "versionIri", location, failures);
        if (versionIri.isPresent() && ontologyIri.isEmpty()) {
            failures.add(new MigrationFailure("IC-5", location, "version IRI requires an ontology IRI"));
            return;
        }
        RegisteredOntology ontology = new RegisteredOntology(key, ontologyIri, versionIri);
        byKey.put(key, ontology);
        registered.add(ontology);
    }

    private RevisionDraft readRevisions(
            Path root, Path projectDir, List<RegisteredOntology> ontologies, List<MigrationFailure> failures) {
        Path file = projectDir.resolve(REVISIONS);
        String location = location(projectDir, REVISIONS);
        if (!Files.isRegularFile(file) || !contained(root, file)) {
            failures.add(new MigrationFailure("IC-3", location, "file is missing"));
            return null;
        }
        if (largerThan(file, MAX_REVISION_BYTES)) {
            failures.add(new MigrationFailure("IC-3", location, "file exceeds size limit"));
            return null;
        }
        Map<String, RegisteredOntology> keys = new LinkedHashMap<>();
        for (RegisteredOntology ontology : ontologies) {
            keys.put(ontology.key().value(), ontology);
        }
        RevisionDraft draft = new RevisionDraft();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                readRevisionLine(location, line, lineNumber, keys, draft, failures);
            }
        } catch (IOException ex) {
            failures.add(new MigrationFailure("IC-3", location, "cannot read revisions"));
            return null;
        }
        return draft;
    }

    private void readRevisionLine(
            String location,
            String line,
            int lineNumber,
            Map<String, RegisteredOntology> keys,
            RevisionDraft draft,
            List<MigrationFailure> failures) {
        String where = location + ":line " + lineNumber;
        if (line.isEmpty()) {
            failures.add(new MigrationFailure("IC-3", where, "not a JSON object"));
            draft.unreadable();
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JacksonException ex) {
            failures.add(new MigrationFailure("IC-3", where, "not a JSON object"));
            draft.unreadable();
            return;
        }
        if (!node.isObject()) {
            failures.add(new MigrationFailure("IC-3", where, "not a JSON object"));
            draft.unreadable();
            return;
        }
        Long number = integral(node.get("rev"));
        draft.noteNumber(number);
        JsonNode changes = node.get("changes");
        if (changes == null || !changes.isArray()) {
            failures.add(new MigrationFailure("IC-4", where, "changes must be an array"));
            return;
        }
        draft.countChanges(changes.size());
        long rev = number == null ? lineNumber : number;
        List<OntologyChange> decoded = decodeChanges(location, rev, changes, keys, failures);
        UserId user = user(node.get("user"), where, failures);
        Instant timestamp = timestamp(node.get("ts"), where, failures);
        String description = description(node.get("desc"), where, failures);
        if (decoded == null
                || user == null
                || timestamp == null
                || description == null
                || number == null
                || number < 1) {
            return;
        }
        draft.add(new MigrationRevision(RevisionNumber.of(number), user, timestamp, description, decoded));
    }

    private List<OntologyChange> decodeChanges(
            String location,
            long rev,
            JsonNode changes,
            Map<String, RegisteredOntology> keys,
            List<MigrationFailure> failures) {
        List<OntologyChange> decoded = new ArrayList<>();
        boolean ok = true;
        int index = 0;
        for (JsonNode change : changes) {
            if (!decodeChange(location, rev, index, change, keys, decoded, failures)) {
                ok = false;
            }
            index++;
        }
        return ok ? List.copyOf(decoded) : null;
    }

    private boolean decodeChange(
            String location,
            long rev,
            int index,
            JsonNode change,
            Map<String, RegisteredOntology> keys,
            List<OntologyChange> decoded,
            List<MigrationFailure> failures) {
        String where = location + ":rev " + rev + " changeIndex " + index;
        if (change == null || !change.isString()) {
            failures.add(new MigrationFailure("IC-6", where, "change must be a string"));
            return false;
        }
        String text = change.asString();
        String[] parts = text.split("\t", 4);
        if (parts.length == 4 && !keys.containsKey(parts[2])) {
            failures.add(new MigrationFailure("IC-5", where, "ontology key " + parts[2] + " is not registered"));
        }
        try {
            decoded.add(OfnLines.decodeLine(text, dataFactory));
            return parts.length == 4 && keys.containsKey(parts[2]);
        } catch (RuntimeException ex) {
            failures.add(new MigrationFailure("IC-6", where, detail(ex)));
            return false;
        }
    }

    private static void checkRevisionNumbers(
            Path projectDir, JsonNode manifest, RevisionDraft draft, List<MigrationFailure> failures) {
        if (!draft.readable()) {
            return;
        }
        String location = location(projectDir, REVISIONS);
        Long head = integral(manifest.path("stats").get("headRevision"));
        List<Long> numbers = draft.numbers();
        if (numbers.isEmpty()) {
            if (head == null) {
                failures.add(new MigrationFailure(
                        "IC-3", location(projectDir, MANIFEST), "headRevision must be an integer"));
            } else if (head != 0L) {
                failures.add(new MigrationFailure("IC-3", location, "empty log requires headRevision 0"));
            }
            return;
        }
        for (int index = 0; index < numbers.size(); index++) {
            Long rev = numbers.get(index);
            if (rev == null || rev != index + 1L) {
                failures.add(new MigrationFailure(
                        "IC-3",
                        location + ":line " + (index + 1),
                        "revision numbers must start at 1 and increase by 1"));
                return;
            }
        }
        long last = numbers.get(numbers.size() - 1);
        if (head == null) {
            failures.add(
                    new MigrationFailure("IC-3", location(projectDir, MANIFEST), "headRevision must be an integer"));
        } else if (head != last) {
            failures.add(new MigrationFailure(
                    "IC-3",
                    location(projectDir, MANIFEST),
                    "headRevision " + head + " does not equal last revision " + last));
        }
    }

    private static void checkChangeCount(
            Path projectDir, JsonNode manifest, RevisionDraft draft, List<MigrationFailure> failures) {
        if (!draft.readable()) {
            return;
        }
        Long expected = integral(manifest.path("stats").get("changeCount"));
        if (expected == null) {
            failures.add(
                    new MigrationFailure("IC-4", location(projectDir, MANIFEST), "changeCount must be an integer"));
            return;
        }
        if (expected != draft.changeCount()) {
            failures.add(new MigrationFailure(
                    "IC-4",
                    location(projectDir, MANIFEST),
                    "changeCount " + expected + " does not equal " + draft.changeCount()));
        }
    }

    private static Optional<IRI> iri(JsonNode node, String field, String location, List<MigrationFailure> failures) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString() || value.asString().isBlank()) {
            failures.add(new MigrationFailure("IC-5", location, field + " must be an IRI or null"));
            return Optional.empty();
        }
        try {
            return Optional.of(IRI.create(value.asString()));
        } catch (RuntimeException ex) {
            failures.add(new MigrationFailure("IC-5", location, field + " is not an IRI"));
            return Optional.empty();
        }
    }

    private static String text(JsonNode details, String field, Path projectDir, List<MigrationFailure> failures) {
        if (details == null || details.isMissingNode() || details.isNull()) {
            return "";
        }
        JsonNode value = details.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isString()) {
            failures.add(new MigrationFailure("IC-2", location(projectDir, MANIFEST), field + " must be a string"));
            return "";
        }
        return value.asString();
    }

    private static boolean flag(JsonNode details, Path projectDir, List<MigrationFailure> failures) {
        if (details == null || details.isMissingNode() || details.isNull()) {
            return false;
        }
        JsonNode value = details.get("inTrash");
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            failures.add(new MigrationFailure("IC-2", location(projectDir, MANIFEST), "inTrash must be a boolean"));
            return false;
        }
        return value.asBoolean();
    }

    private static Instant epoch(JsonNode details, String field, Path projectDir, List<MigrationFailure> failures) {
        if (details == null || details.isMissingNode() || details.isNull()) {
            return Instant.EPOCH;
        }
        JsonNode raw = details.get(field);
        if (raw == null || raw.isNull()) {
            return Instant.EPOCH;
        }
        Long value = integral(raw);
        if (value == null) {
            failures.add(new MigrationFailure("IC-2", location(projectDir, MANIFEST), field + " must be an integer"));
            return Instant.EPOCH;
        }
        return Instant.ofEpochMilli(value);
    }

    private static UserId user(JsonNode node, String location, List<MigrationFailure> failures) {
        if (node == null || !node.isString() || node.asString().isBlank()) {
            failures.add(new MigrationFailure("IC-3", location, "user is required"));
            return null;
        }
        try {
            return new UserId(node.asString());
        } catch (IllegalArgumentException ex) {
            failures.add(new MigrationFailure("IC-3", location, detail(ex)));
            return null;
        }
    }

    private static Instant timestamp(JsonNode node, String location, List<MigrationFailure> failures) {
        Long value = integral(node);
        if (value == null) {
            failures.add(new MigrationFailure("IC-3", location, "ts must be an integer"));
            return null;
        }
        return Instant.ofEpochMilli(value);
    }

    private static String description(JsonNode node, String location, List<MigrationFailure> failures) {
        if (node == null || !node.isString()) {
            failures.add(new MigrationFailure("IC-3", location, "desc must be a string"));
            return null;
        }
        return node.asString();
    }

    private static Long integral(JsonNode node) {
        if (node == null
                || node.isNull()
                || node.isMissingNode()
                || !node.isIntegralNumber()
                || !node.canConvertToLong()) {
            return null;
        }
        return node.longValue();
    }

    private static List<Path> directories(Path projects, List<MigrationFailure> failures) {
        try (Stream<Path> children = Files.list(projects)) {
            List<Path> accepted = new ArrayList<>();
            for (Path dir : children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                String name = dir.getFileName().toString();
                try {
                    ProjectId.of(name);
                } catch (IllegalArgumentException ex) {
                    failures.add(new MigrationFailure(
                            "package", "projects/" + name, "directory name is not a project UUID"));
                    continue;
                }
                accepted.add(dir);
            }
            return accepted;
        } catch (IOException ex) {
            failures.add(new MigrationFailure("package", "projects", "cannot list projects"));
            return List.of();
        }
    }

    private static boolean contained(Path root, Path file) {
        try {
            return file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean bareName(String name) {
        return !name.isBlank()
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && !name.contains("..")
                && name.equals(Path.of(name).getFileName().toString());
    }

    private static boolean largerThan(Path file, long limit) {
        try {
            return Files.size(file) > limit;
        } catch (IOException ex) {
            return true;
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return List.of();
        }
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
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static Path realDirectory(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException ex) {
            throw failure("package", safeName(directory), "cannot read package directory");
        }
    }

    private static InvalidMigrationPackageException failure(String rule, String location, String detail) {
        return new InvalidMigrationPackageException(List.of(new MigrationFailure(rule, location, detail)));
    }

    private static String location(Path projectDir, String name) {
        return "projects/" + projectDir.getFileName() + "/" + name;
    }

    private static String safeName(Path path) {
        Path name = path.getFileName();
        return name == null ? "package" : name.toString();
    }

    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String flat = message.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= DETAIL_LIMIT) {
            return flat;
        }
        return flat.substring(0, DETAIL_LIMIT) + "...";
    }
}
