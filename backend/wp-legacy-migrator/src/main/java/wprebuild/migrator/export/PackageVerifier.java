package wprebuild.migrator.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.IRI;

/** Checks IC-1 through IC-6 for every project in a migration package. */
public final class PackageVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public List<Failure> check(Path packageDirectory) {
        Objects.requireNonNull(packageDirectory, "packageDirectory");
        Path projects = packageDirectory.resolve("projects");
        if (!Files.isDirectory(projects)) {
            return List.of(new Failure("package", packageDirectory.toString(), "projects directory is missing"));
        }
        List<Path> projectDirs;
        try {
            projectDirs = projectDirectories(projects);
        } catch (UncheckedIOException exception) {
            return List.of(new Failure("package", projects.toString(), ioDetail(exception)));
        }
        List<Failure> failures = new ArrayList<>();
        for (Path projectDir : projectDirs) {
            try {
                checkProject(failures, projectDir);
            } catch (UncheckedIOException exception) {
                failures.add(new Failure("package", "projects/" + projectDir.getFileName(), ioDetail(exception)));
            }
        }
        return List.copyOf(failures);
    }

    private static List<Path> projectDirectories(Path projects) {
        try (Stream<Path> children = Files.list(projects)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot list " + projects, exception);
        }
    }

    private void checkProject(List<Failure> failures, Path projectDir) {
        checkChecksums(failures, projectDir);
        JsonNode manifest = readManifest(failures, projectDir);
        if (manifest == null) {
            return;
        }
        checkFormat(failures, projectDir, manifest);
        List<JsonNode> revisions = readRevisions(failures, projectDir);
        if (revisions == null) {
            return;
        }
        checkRevisionNumbers(failures, projectDir, manifest, revisions);
        checkChangeCount(failures, projectDir, manifest, revisions);
        checkChangeLines(failures, projectDir, manifest, revisions);
    }

    private static void checkChecksums(List<Failure> failures, Path projectDir) {
        Path sums = projectDir.resolve(PackageFiles.CHECKSUMS);
        String location = location(projectDir, PackageFiles.CHECKSUMS);
        if (!Files.isRegularFile(sums)) {
            failures.add(new Failure("IC-1", location, "file is missing"));
            return;
        }
        List<String> lines = readLines(sums);
        Map<String, String> listed = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            parseChecksumLine(failures, projectDir, location, listed, lines.get(index), index + 1);
        }
        for (String name : PackageFiles.CHECKED) {
            if (!listed.containsKey(name)) {
                failures.add(new Failure("IC-1", location(projectDir, name), "missing from SHA256SUMS"));
            }
        }
    }

    private static void parseChecksumLine(
            List<Failure> failures,
            Path projectDir,
            String location,
            Map<String, String> listed,
            String line,
            int lineNumber) {
        if (line.isEmpty()) {
            return;
        }
        int split = line.indexOf("  ");
        if (split != 64 || !SHA256.matcher(line.substring(0, split)).matches()) {
            failures.add(new Failure("IC-1", location + ":line " + lineNumber, "malformed checksum line"));
            return;
        }
        String hash = line.substring(0, split);
        String name = line.substring(split + 2);
        if (name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            failures.add(new Failure("IC-1", location + ":line " + lineNumber, "entry is not a bare file name"));
            return;
        }
        String previous = listed.put(name, hash);
        if (previous != null && !previous.equals(hash)) {
            failures.add(new Failure("IC-1", location, "conflicting checksums for " + name));
        }
        Path file = projectDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            failures.add(new Failure("IC-1", location(projectDir, name), "file is missing"));
            return;
        }
        try {
            if (!hash.equals(PackageFiles.sha256(file))) {
                failures.add(new Failure("IC-1", location(projectDir, name), "SHA-256 mismatch"));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot hash " + file, exception);
        }
    }

    private static void checkFormat(List<Failure> failures, Path projectDir, JsonNode manifest) {
        JsonNode version = manifest.get("formatVersion");
        if (version == null
                || !version.isIntegralNumber()
                || !version.canConvertToLong()
                || version.longValue() != 1L) {
            failures.add(new Failure("IC-2", location(projectDir, PackageFiles.MANIFEST), "formatVersion must be 1"));
        }
    }

    private static void checkRevisionNumbers(
            List<Failure> failures, Path projectDir, JsonNode manifest, List<JsonNode> revisions) {
        String location = location(projectDir, PackageFiles.REVISIONS);
        Long head = integral(manifest.path("stats").get("headRevision"));
        if (revisions.isEmpty()) {
            if (head == null) {
                failures.add(new Failure(
                        "IC-3", location(projectDir, PackageFiles.MANIFEST), "headRevision must be an integer"));
            } else if (head != 0) {
                failures.add(new Failure("IC-3", location, "empty log requires headRevision 0"));
            }
            return;
        }
        for (int index = 0; index < revisions.size(); index++) {
            JsonNode rev = revisions.get(index).get("rev");
            long expected = index + 1L;
            if (rev == null || !rev.isIntegralNumber() || rev.asLong() != expected) {
                failures.add(new Failure(
                        "IC-3",
                        location + ":line " + (index + 1),
                        "revision numbers must start at 1 and increase by 1"));
                return;
            }
        }
        long last = revisions.get(revisions.size() - 1).get("rev").asLong();
        if (head == null) {
            failures.add(new Failure(
                    "IC-3", location(projectDir, PackageFiles.MANIFEST), "headRevision must be an integer"));
        } else if (head != last) {
            failures.add(new Failure(
                    "IC-3",
                    location(projectDir, PackageFiles.MANIFEST),
                    "headRevision " + head + " does not equal last revision " + last));
        }
    }

    private static void checkChangeCount(
            List<Failure> failures, Path projectDir, JsonNode manifest, List<JsonNode> revisions) {
        Long expected = integral(manifest.path("stats").get("changeCount"));
        if (expected == null) {
            failures.add(
                    new Failure("IC-4", location(projectDir, PackageFiles.MANIFEST), "changeCount must be an integer"));
            return;
        }
        long actual = 0;
        for (int index = 0; index < revisions.size(); index++) {
            JsonNode changes = revisions.get(index).get("changes");
            if (changes == null || !changes.isArray()) {
                failures.add(new Failure(
                        "IC-4",
                        location(projectDir, PackageFiles.REVISIONS) + ":line " + (index + 1),
                        "changes must be an array"));
                return;
            }
            actual += changes.size();
        }
        if (actual != expected) {
            failures.add(new Failure(
                    "IC-4",
                    location(projectDir, PackageFiles.MANIFEST),
                    "changeCount " + expected + " does not equal " + actual));
        }
    }

    private static void checkChangeLines(
            List<Failure> failures, Path projectDir, JsonNode manifest, List<JsonNode> revisions) {
        Set<String> keys = ontologyKeys(failures, projectDir, manifest);
        FunctionalOfn syntax = new FunctionalOfn();
        for (JsonNode revision : revisions) {
            JsonNode changes = revision.get("changes");
            if (changes == null || !changes.isArray()) {
                continue;
            }
            long rev = revision.path("rev").asLong();
            for (int index = 0; index < changes.size(); index++) {
                checkOneChange(failures, projectDir, syntax, keys, rev, index, changes.get(index));
            }
        }
    }

    private static Set<String> ontologyKeys(List<Failure> failures, Path projectDir, JsonNode manifest) {
        JsonNode ontologies = manifest.get("ontologies");
        Set<String> keys = new HashSet<>();
        if (ontologies == null || !ontologies.isArray()) {
            failures.add(
                    new Failure("IC-5", location(projectDir, PackageFiles.MANIFEST), "ontologies must be an array"));
            return keys;
        }
        for (JsonNode ontology : ontologies) {
            JsonNode key = ontology.get("key");
            if (key != null && key.isTextual()) {
                keys.add(key.asText());
            }
        }
        return keys;
    }

    private static void checkOneChange(
            List<Failure> failures,
            Path projectDir,
            FunctionalOfn syntax,
            Set<String> keys,
            long rev,
            int index,
            JsonNode changeNode) {
        String where = location(projectDir, PackageFiles.REVISIONS) + ":rev " + rev + " changeIndex " + index;
        if (changeNode == null || !changeNode.isTextual()) {
            failures.add(new Failure("IC-6", where, "change must be a string"));
            return;
        }
        String[] parts = changeNode.asText().split("\t", 4);
        if (parts.length != 4) {
            failures.add(new Failure("IC-6", where, "change line must have op, kind, ontologyKey, and ofn"));
            return;
        }
        if (!keys.contains(parts[2])) {
            failures.add(new Failure("IC-5", where, "ontology key " + parts[2] + " is not registered"));
        }
        try {
            parse(syntax, parts[0], parts[1], parts[3]);
        } catch (IllegalArgumentException exception) {
            failures.add(new Failure("IC-6", where, detail(exception)));
        }
    }

    private static void parse(FunctionalOfn syntax, String op, String kind, String ofn) {
        switch (kind) {
            case "AXIOM" -> {
                requireAddOrRemove(op);
                syntax.parseAxiom(ofn);
            }
            case "ANNOTATION" -> {
                requireAddOrRemove(op);
                syntax.parseAnnotation(ofn);
            }
            case "IMPORT" -> {
                requireAddOrRemove(op);
                angleIri(ofn);
            }
            case "ONTOLOGY" -> ontologyOfn(op, ofn);
            default -> throw new IllegalArgumentException("unknown change kind: " + kind);
        }
    }

    private static void requireAddOrRemove(String op) {
        if (!"+".equals(op) && !"-".equals(op)) {
            throw new IllegalArgumentException("op must be + or -");
        }
    }

    private static void ontologyOfn(String op, String ofn) {
        if (!"+".equals(op)) {
            throw new IllegalArgumentException("ONTOLOGY changes cannot be removed (ADR-011)");
        }
        if ("-".equals(ofn)) {
            return;
        }
        String[] tokens = ofn.split(" ", -1);
        if (tokens.length < 1 || tokens.length > 2) {
            throw new IllegalArgumentException("ontology id must be '-' or one or two IRIs");
        }
        for (String token : tokens) {
            angleIri(token);
        }
    }

    private static void angleIri(String ofn) {
        if (ofn.length() < 3 || ofn.charAt(0) != '<' || ofn.charAt(ofn.length() - 1) != '>') {
            throw new IllegalArgumentException("IRI must be wrapped in angle brackets");
        }
        String text = ofn.substring(1, ofn.length() - 1);
        if (text.isBlank()
                || text.indexOf('<') >= 0
                || text.indexOf('>') >= 0
                || text.indexOf(' ') >= 0
                || text.indexOf('\t') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("IRI cannot be written in angle brackets: " + text);
        }
        IRI.create(text);
    }

    private static JsonNode readManifest(List<Failure> failures, Path projectDir) {
        Path file = projectDir.resolve(PackageFiles.MANIFEST);
        if (!Files.isRegularFile(file)) {
            failures.add(new Failure("IC-2", location(projectDir, PackageFiles.MANIFEST), "file is missing"));
            return null;
        }
        try {
            return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            failures.add(new Failure("IC-2", location(projectDir, PackageFiles.MANIFEST), "not JSON"));
            return null;
        }
    }

    private static List<JsonNode> readRevisions(List<Failure> failures, Path projectDir) {
        Path file = projectDir.resolve(PackageFiles.REVISIONS);
        String location = location(projectDir, PackageFiles.REVISIONS);
        if (!Files.isRegularFile(file)) {
            failures.add(new Failure("IC-3", location, "file is missing"));
            return null;
        }
        List<String> lines = readLines(file);
        List<JsonNode> revisions = new ArrayList<>();
        boolean readable = true;
        for (int index = 0; index < lines.size(); index++) {
            try {
                JsonNode node = JSON.readTree(lines.get(index));
                if (!node.isObject()) {
                    throw new IllegalArgumentException("not an object");
                }
                revisions.add(node);
            } catch (IOException | IllegalArgumentException exception) {
                failures.add(new Failure("IC-3", location + ":line " + (index + 1), "not a JSON object"));
                readable = false;
            }
        }
        return readable ? revisions : null;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read " + file, exception);
        }
    }

    private static Long integral(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        return node.longValue();
    }

    private static String location(Path projectDir, String name) {
        return "projects/" + projectDir.getFileName() + "/" + name;
    }

    private static String ioDetail(UncheckedIOException exception) {
        String message = exception.getMessage() == null ? "cannot read package" : exception.getMessage();
        Throwable cause = exception.getCause();
        if (cause == null || cause.getMessage() == null || message.contains(cause.getMessage())) {
            return message;
        }
        return message + ": " + cause.getMessage();
    }

    private static String detail(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    /** One failed rule. {@code location} is a package-relative path, plus a revision when known. */
    public record Failure(String rule, String location, String detail) {}
}
