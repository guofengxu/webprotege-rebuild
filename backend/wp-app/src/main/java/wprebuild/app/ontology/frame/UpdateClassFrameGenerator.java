package wprebuild.app.ontology.frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import wprebuild.app.shared.ConflictException;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Writes the axiom difference between two class frames.
 *
 * <p>The stored frame must still equal {@code from}. {@code otherAxioms} stay in that comparison, so
 * an edit of an unsupported axiom conflicts, but they are not themselves added or removed.
 */
public final class UpdateClassFrameGenerator implements ChangeListGenerator<ClassFrame> {

    public static final String FRAME_CHANGED = "FRAME_CHANGED";

    private final ClassFrame from;

    private final ClassFrame to;

    private final ClassFrameQuery frames;

    private final ProjectRuntime runtime;

    private final OntologyKey targetOntology;

    private String displayName;

    public UpdateClassFrameGenerator(
            ClassFrame from,
            ClassFrame to,
            ClassFrameQuery frames,
            ProjectRuntime runtime,
            OntologyKey targetOntology) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.targetOntology = Objects.requireNonNull(targetOntology, "target ontology");
    }

    @Override
    public GeneratedChanges<ClassFrame> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        ClassFrame current = frames.frame(runtime, IRI.create(from.subject().iri()));
        if (!current.equals(from)) {
            throw new ConflictException(FRAME_CHANGED, "class frame changed");
        }
        displayName = current.subject().displayName();
        Set<OWLAxiom> before = ClassFrameAxioms.toAxioms(from);
        Set<OWLAxiom> after = ClassFrameAxioms.toAxioms(to);
        return new GeneratedChanges<>(diff(context.ontologies(), before, after), to);
    }

    @Override
    public String describe(GeneratedChanges<ClassFrame> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        return "Edited class " + Objects.requireNonNull(displayName, "display name");
    }

    private List<OntologyChange> diff(ProjectOntologies ontologies, Set<OWLAxiom> before, Set<OWLAxiom> after) {
        LinkedHashSet<OWLAxiom> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        LinkedHashSet<OWLAxiom> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        List<OntologyKey> keys = keys(ontologies);
        List<OntologyChange> changes = new ArrayList<>();
        LinkedHashSet<OntologyKey> touched = new LinkedHashSet<>();
        for (OWLAxiom axiom : removed) {
            remove(ontologies, keys, axiom, changes, touched);
        }
        if (added.isEmpty()) {
            return List.copyOf(changes);
        }
        OntologyKey destination = destination(ontologies, keys, before, touched);
        for (OWLAxiom axiom : added) {
            changes.add(new AddAxiom(destination, axiom));
        }
        return List.copyOf(changes);
    }

    private static void remove(
            ProjectOntologies ontologies,
            List<OntologyKey> keys,
            OWLAxiom axiom,
            List<OntologyChange> changes,
            Set<OntologyKey> touched) {
        for (OntologyKey key : keys) {
            // The frame omits axiom annotations, so the stored axiom may not equal the rebuilt one.
            for (OWLAxiom stored : storedMatches(ontologies, key, axiom)) {
                changes.add(new RemoveAxiom(key, stored));
                touched.add(key);
            }
        }
    }

    private static boolean containsLogical(ProjectOntologies ontologies, OntologyKey key, OWLAxiom axiom) {
        return !storedMatches(ontologies, key, axiom).isEmpty();
    }

    private static List<OWLAxiom> storedMatches(ProjectOntologies ontologies, OntologyKey key, OWLAxiom rebuilt) {
        return candidates(ontologies, rebuilt)
                .filter(stored -> stored.equalsIgnoreAnnotations(rebuilt) && ontologies.containsAxiom(key, stored))
                .toList();
    }

    private static Stream<OWLAxiom> candidates(ProjectOntologies ontologies, OWLAxiom rebuilt) {
        return switch (rebuilt) {
            case OWLAnnotationAssertionAxiom assertion
            when assertion.getSubject() instanceof IRI subject ->
                ontologies.annotationAssertions(subject).map(OWLAxiom.class::cast);
            case OWLSubClassOfAxiom ignored ->
                ontologies.axiomsOfType(AxiomType.SUBCLASS_OF).map(OWLAxiom.class::cast);
            default -> throw new IllegalArgumentException("frame axiom must be an annotation or subclass axiom");
        };
    }

    private OntologyKey destination(
            ProjectOntologies ontologies,
            List<OntologyKey> keys,
            Set<OWLAxiom> before,
            LinkedHashSet<OntologyKey> touched) {
        if (touched.size() == 1) {
            return touched.getFirst();
        }
        for (OntologyKey key : keys) {
            for (OWLAxiom axiom : before) {
                if (containsLogical(ontologies, key, axiom)) {
                    return key;
                }
            }
        }
        if (keys.contains(targetOntology)) {
            return targetOntology;
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("project has no ontology");
        }
        return keys.getFirst();
    }

    private static List<OntologyKey> keys(ProjectOntologies ontologies) {
        return ontologies.keys().stream()
                .sorted(Comparator.comparingInt(
                        key -> Integer.parseInt(key.value().substring(1))))
                .toList();
    }
}
