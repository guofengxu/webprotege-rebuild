package wprebuild.app.migration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnLines;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * IC-7 and IC-8. Both sides are OWL API 5 objects. {@link OwlApiProjectOntologies} records imports
 * and does not load them, so comparison does not touch the network.
 */
final class HeadEquivalence {

    private static final int DIFF_LIMIT = 20;

    /** Uncompressed head text. Stops a tiny gzip from expanding without bound. */
    private static final long MAX_HEAD_BYTES = 512L * 1024 * 1024;

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private HeadEquivalence() {}

    static List<MigrationFailure> check(List<OntologyChange> replayed, MigrationProject project) {
        String where = project.location() + "/" + MigrationPackageReader.HEAD;
        ProjectOntologies replay;
        ProjectOntologies head;
        try {
            replay = apply(replayed);
            head = apply(headChanges(project, referenced(replayed)));
        } catch (HeadRule ex) {
            return List.of(new MigrationFailure(ex.rule, where, ex.getMessage()));
        } catch (RuntimeException ex) {
            return List.of(new MigrationFailure("IC-7", where, detail(ex)));
        }
        List<MigrationFailure> failures = new ArrayList<>();
        String structure = structuralDifference(replay, head);
        if (!structure.isEmpty()) {
            failures.add(new MigrationFailure("IC-7", where, structure));
        }
        String counts = countDifference(replay, head);
        if (!counts.isEmpty()) {
            failures.add(new MigrationFailure("IC-8", where, counts));
        }
        return failures;
    }

    private static ProjectOntologies apply(List<OntologyChange> changes) {
        OwlApiProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(changes);
        return ontologies;
    }

    private static List<OntologyChange> headChanges(MigrationProject project, Set<OntologyKey> referenced) {
        List<String> lines = physicalLines(gunzip(project.headFile()));
        List<OntologyChange> additions = new ArrayList<>();
        Set<OntologyKey> used = new LinkedHashSet<>();
        Set<OntologyKey> declared = new HashSet<>();
        int lineNumber = 0;
        for (String physical : lines) {
            lineNumber++;
            if (physical.isEmpty()) {
                continue;
            }
            OntologyChange change = OfnLines.decodeLine(OfnLines.unescapeLine(physical), DATA);
            if (!addition(change)) {
                throw new IllegalArgumentException("head line " + lineNumber + " is not an addition");
            }
            if (change instanceof DeclareOntology) {
                declared.add(change.ontology());
            }
            used.add(change.ontology());
            additions.add(change);
        }
        Map<OntologyKey, RegisteredOntology> byKey = new LinkedHashMap<>();
        for (RegisteredOntology ontology : project.ontologies()) {
            byKey.put(ontology.key(), ontology);
        }
        Set<OntologyKey> needed = new LinkedHashSet<>(referenced);
        needed.addAll(used);
        for (OntologyKey key : needed) {
            if (!byKey.containsKey(key)) {
                throw new HeadRule("IC-5", "ontology key " + key.value() + " is not registered");
            }
        }
        List<OntologyChange> changes = new ArrayList<>();
        for (RegisteredOntology ontology : project.ontologies()) {
            if (needed.contains(ontology.key()) && !declared.contains(ontology.key())) {
                changes.add(ontology.declare());
            }
        }
        changes.addAll(additions);
        return List.copyOf(changes);
    }

    private static Set<OntologyKey> referenced(List<OntologyChange> changes) {
        Set<OntologyKey> keys = new LinkedHashSet<>();
        for (OntologyChange change : changes) {
            keys.add(change.ontology());
        }
        return keys;
    }

    private static boolean addition(OntologyChange change) {
        return change instanceof AddAxiom
                || change instanceof AddOntologyAnnotation
                || change instanceof AddImport
                || change instanceof DeclareOntology;
    }

    private static String structuralDifference(ProjectOntologies replay, ProjectOntologies head) {
        List<String> diffs = new ArrayList<>();
        for (OntologyKey key : union(replay, head)) {
            compareKey(diffs, key, replay, head);
        }
        return join(diffs);
    }

    private static void compareKey(
            List<String> diffs, OntologyKey key, ProjectOntologies replay, ProjectOntologies head) {
        String name = key.value();
        if (!replay.info(key).equals(head.info(key))) {
            diffs.add(name + " ontology id differs");
        }
        compareSet(diffs, name + " axiom", axioms(replay, key), axioms(head, key));
        compareSet(diffs, name + " annotation", annotations(replay, key), annotations(head, key));
        compareSet(diffs, name + " import", imports(replay, key), imports(head, key));
    }

    private static void compareSet(List<String> diffs, String label, Set<?> replay, Set<?> head) {
        if (replay.equals(head)) {
            return;
        }
        Set<Object> onlyHead = new LinkedHashSet<>(head);
        onlyHead.removeAll(replay);
        Set<Object> onlyReplay = new LinkedHashSet<>(replay);
        onlyReplay.removeAll(head);
        diffs.add(label + " head=" + head.size() + " replay=" + replay.size());
        example(diffs, label + " only in head", onlyHead);
        example(diffs, label + " only in replay", onlyReplay);
    }

    private static void example(List<String> diffs, String label, Set<Object> values) {
        if (values.isEmpty()) {
            return;
        }
        diffs.add(label + ": " + shorten(values.iterator().next()));
    }

    private static String countDifference(ProjectOntologies replay, ProjectOntologies head) {
        List<CountGap> gaps = new ArrayList<>();
        for (OntologyKey key : union(replay, head)) {
            Map<String, Long> replayCounts = counts(replay, key);
            Map<String, Long> headCounts = counts(head, key);
            Set<String> types = new LinkedHashSet<>();
            types.addAll(replayCounts.keySet());
            types.addAll(headCounts.keySet());
            for (String type : types) {
                long replayCount = replayCounts.getOrDefault(type, 0L);
                long headCount = headCounts.getOrDefault(type, 0L);
                if (replayCount != headCount) {
                    gaps.add(new CountGap(key.value(), type, replayCount, headCount));
                }
            }
        }
        if (gaps.isEmpty()) {
            return "";
        }
        gaps.sort(Comparator.comparingLong(CountGap::distance)
                .reversed()
                .thenComparing(CountGap::key)
                .thenComparing(CountGap::type));
        List<String> lines = new ArrayList<>();
        int shown = Math.min(gaps.size(), DIFF_LIMIT);
        for (int index = 0; index < shown; index++) {
            lines.add(gaps.get(index).format());
        }
        if (gaps.size() > shown) {
            lines.add("showing " + shown + " of " + gaps.size() + " axiom-type differences");
        }
        return join(lines);
    }

    private static Map<String, Long> counts(ProjectOntologies ontologies, OntologyKey key) {
        if (!ontologies.keys().contains(key)) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        ontologies
                .axioms(key)
                .forEach(axiom -> counts.merge(axiom.getAxiomType().getName(), 1L, Long::sum));
        return counts;
    }

    private static List<OntologyKey> union(ProjectOntologies replay, ProjectOntologies head) {
        Set<OntologyKey> keys = new LinkedHashSet<>();
        keys.addAll(replay.keys());
        keys.addAll(head.keys());
        return keys.stream().sorted(Comparator.comparing(OntologyKey::value)).toList();
    }

    private static Set<OWLAxiom> axioms(ProjectOntologies ontologies, OntologyKey key) {
        if (!ontologies.keys().contains(key)) {
            return Set.of();
        }
        return ontologies.axioms(key).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<OWLAnnotation> annotations(ProjectOntologies ontologies, OntologyKey key) {
        if (!ontologies.keys().contains(key)) {
            return Set.of();
        }
        return ontologies.annotations(key);
    }

    private static Set<IRI> imports(ProjectOntologies ontologies, OntologyKey key) {
        if (!ontologies.keys().contains(key)) {
            return Set.of();
        }
        return ontologies.imports(key);
    }

    private static String gunzip(Path file) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_HEAD_BYTES) {
                    throw new IllegalArgumentException("head baseline exceeds " + MAX_HEAD_BYTES + " bytes");
                }
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("cannot read head baseline");
        }
    }

    private static List<String> physicalLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        String[] parts = text.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < parts.length; index++) {
            String line = parts[index];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty() && index == parts.length - 1) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    private static String join(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        int shown = Math.min(lines.size(), DIFF_LIMIT);
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                text.append("; ");
            }
            text.append(lines.get(index));
        }
        if (lines.size() > shown) {
            text.append("; and ").append(lines.size() - shown).append(" more");
        }
        return text.toString();
    }

    private static String shorten(Object value) {
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        if (text.length() <= 160) {
            return text;
        }
        return text.substring(0, 160) + "...";
    }

    private static String detail(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return shorten(message);
    }

    /** A head-baseline failure that already has an interchange rule id. */
    private static final class HeadRule extends RuntimeException {

        private final String rule;

        private HeadRule(String rule, String detail) {
            super(detail);
            this.rule = rule;
        }
    }

    private record CountGap(String key, String type, long replay, long head) {

        long distance() {
            return Math.abs(replay - head);
        }

        String format() {
            return key + " " + type + " replay=" + replay + " head=" + head;
        }
    }
}
