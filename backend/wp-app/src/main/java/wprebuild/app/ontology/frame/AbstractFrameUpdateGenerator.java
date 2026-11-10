package wprebuild.app.ontology.frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
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
 * Writes the axiom difference between two frames of the same entity.
 *
 * <p>The stored frame must still equal {@code from}. {@code otherAxioms} stay in that comparison, so
 * an edit of an unsupported axiom conflicts, but they are not themselves added or removed. A stored
 * axiom matches its rebuilt form when they are equal ignoring axiom annotations.
 */
public abstract class AbstractFrameUpdateGenerator<F> implements ChangeListGenerator<F> {

    public static final String FRAME_CHANGED = "FRAME_CHANGED";

    private final F from;

    private final F to;

    private final ProjectRuntime runtime;

    private final OntologyKey targetOntology;

    private final String kind;

    private String displayName;

    protected AbstractFrameUpdateGenerator(
            F from, F to, ProjectRuntime runtime, OntologyKey targetOntology, String kind) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.targetOntology = Objects.requireNonNull(targetOntology, "target ontology");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind is blank");
        }
    }

    protected final F from() {
        return from;
    }

    protected final ProjectRuntime runtime() {
        return runtime;
    }

    /** Frame currently stored. The caller already holds the generation lock. */
    protected abstract F currentFrame();

    /** Editable axioms of {@code frame}. {@code otherAxioms} are omitted. */
    protected abstract Set<OWLAxiom> toAxioms(F frame);

    protected abstract String label(F frame);

    /** Axioms that might be the stored form of {@code rebuilt}, including axiom annotations. */
    protected abstract Stream<OWLAxiom> candidates(ProjectOntologies ontologies, OWLAxiom rebuilt);

    @Override
    public final GeneratedChanges<F> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        F current = currentFrame();
        if (!current.equals(from)) {
            throw new ConflictException(FRAME_CHANGED, kind + " frame changed");
        }
        displayName = label(current);
        return new GeneratedChanges<>(diff(context.ontologies(), toAxioms(from), toAxioms(to)), to);
    }

    @Override
    public final String describe(GeneratedChanges<F> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        return "Edited " + kind + " " + Objects.requireNonNull(displayName, "display name");
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

    private void remove(
            ProjectOntologies ontologies,
            List<OntologyKey> keys,
            OWLAxiom axiom,
            List<OntologyChange> changes,
            Set<OntologyKey> touched) {
        for (OntologyKey key : keys) {
            for (OWLAxiom stored : storedMatches(ontologies, key, axiom)) {
                changes.add(new RemoveAxiom(key, stored));
                touched.add(key);
            }
        }
    }

    private boolean containsLogical(ProjectOntologies ontologies, OntologyKey key, OWLAxiom axiom) {
        return !storedMatches(ontologies, key, axiom).isEmpty();
    }

    private List<OWLAxiom> storedMatches(ProjectOntologies ontologies, OntologyKey key, OWLAxiom rebuilt) {
        return candidates(ontologies, rebuilt)
                .filter(stored -> stored.equalsIgnoreAnnotations(rebuilt) && ontologies.containsAxiom(key, stored))
                .toList();
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected static Stream<OWLAxiom> ofType(ProjectOntologies ontologies, AxiomType<?> type) {
        return ontologies.axiomsOfType((AxiomType) type).map(OWLAxiom.class::cast);
    }

    private static List<OntologyKey> keys(ProjectOntologies ontologies) {
        return ontologies.keys().stream()
                .sorted(Comparator.comparingInt(
                        key -> Integer.parseInt(key.value().substring(1))))
                .toList();
    }
}
