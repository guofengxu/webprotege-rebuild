package wprebuild.migrator.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.ChangeApplied;
import org.semanticweb.owlapi.model.parameters.Imports;
import wprebuild.migrator.legacy.LegacyRevision;

/**
 * Replays revisions under OWL API 4 and writes the head state as sorted {@code +} lines.
 *
 * <p>Import IRIs are recorded and never loaded.
 */
final class HeadBaseline {

    private static final IRI BLOCKED_IMPORT = IRI.create("urn:wp:blocked-import");

    private HeadBaseline() {}

    static Result capture(List<LegacyRevision> revisions, OntologyKeyAssigner assigner) {
        FunctionalOfn syntax = new FunctionalOfn();
        Map<OWLOntologyID, OWLOntology> ontologies = new LinkedHashMap<>();
        for (LegacyRevision revision : revisions) {
            List<OWLOntologyChangeRecord> records = revision.records();
            for (int index = 0; index < records.size(); index++) {
                apply(ontologies, revision, index, records.get(index));
            }
        }
        List<String> lines = new ArrayList<>();
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (Map.Entry<OWLOntologyID, OWLOntology> entry : ontologies.entrySet()) {
            String key = assigner.keyFor(entry.getKey());
            OWLOntology ontology = entry.getValue();
            counts.put(key, axiomCounts(ontology));
            lines.addAll(linesFor(syntax, key, ontology));
        }
        List<String> physical = new ArrayList<>(lines.size());
        for (String line : lines) {
            physical.add(LineEscape.escape(line));
        }
        physical.sort(Comparator.naturalOrder());
        return new Result(gzip(physical).clone(), freeze(counts));
    }

    private static OWLOntologyManager manager() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.addIRIMapper(iri -> BLOCKED_IMPORT);
        manager.setOntologyLoaderConfiguration(new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setConnectionTimeout(1));
        return manager;
    }

    private static void apply(
            Map<OWLOntologyID, OWLOntology> ontologies,
            LegacyRevision revision,
            int index,
            OWLOntologyChangeRecord record) {
        OWLOntologyID ontologyId = record.getOntologyID();
        OWLOntology ontology = ontologies.get(ontologyId);
        if (ontology == null) {
            // One manager per ontology. A single manager rejects two ontologies that share a
            // document IRI, including a versioned id and an unversioned id with the same IRI.
            OWLOntologyManager manager = manager();
            try {
                ontology = manager.createOntology(ontologyId);
            } catch (OWLOntologyCreationException exception) {
                throw failure(revision, index, exception);
            }
            ontologies.put(ontologyId, ontology);
        }
        ChangeApplied applied;
        try {
            applied = ontology.getOWLOntologyManager()
                    .applyChange(record.getData().createOntologyChange(ontology));
        } catch (RuntimeException exception) {
            throw failure(revision, index, exception);
        }
        if (applied == ChangeApplied.UNSUCCESSFULLY) {
            throw failure(revision, index, new IllegalArgumentException("change was not applied"));
        }
    }

    private static Map<String, Integer> axiomCounts(OWLOntology ontology) {
        Map<String, Integer> counts = new TreeMap<>();
        for (OWLAxiom axiom : ontology.getAxioms(Imports.EXCLUDED)) {
            counts.merge(axiom.getAxiomType().getName(), 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> linesFor(FunctionalOfn syntax, String key, OWLOntology ontology) {
        List<String> lines = new ArrayList<>();
        for (OWLAxiom axiom : ontology.getAxioms(Imports.EXCLUDED)) {
            lines.add(line("AXIOM", key, syntax.renderAxiom(axiom)));
        }
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            lines.add(line("ANNOTATION", key, syntax.renderAnnotation(annotation)));
        }
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            lines.add(line("IMPORT", key, quoted(declaration.getIRI())));
        }
        return lines;
    }

    private static String line(String kind, String key, String ofn) {
        return "+\t" + kind + "\t" + key + "\t" + ofn;
    }

    private static String quoted(IRI iri) {
        String text = iri.toString();
        if (text.isBlank()
                || text.indexOf('<') >= 0
                || text.indexOf('>') >= 0
                || text.indexOf(' ') >= 0
                || text.indexOf('\t') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("import IRI cannot be written in angle brackets: " + text);
        }
        return "<" + text + ">";
    }

    private static byte[] gzip(List<String> lines) {
        String text = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot gzip head baseline", exception);
        }
        return bytes.toByteArray();
    }

    private static Map<String, Map<String, Integer>> freeze(Map<String, Map<String, Integer>> counts) {
        Map<String, Map<String, Integer>> frozen = new LinkedHashMap<>();
        counts.forEach((key, value) -> frozen.put(key, Map.copyOf(value)));
        return Map.copyOf(frozen);
    }

    private static IllegalArgumentException failure(LegacyRevision revision, int index, Exception exception) {
        String message = exception.getMessage();
        String detail =
                message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
        return new IllegalArgumentException(
                "revision %d changeIndex %d: %s".formatted(revision.revision(), index, detail), exception);
    }

    record Result(byte[] gzip, Map<String, Map<String, Integer>> axiomCounts) {}
}
