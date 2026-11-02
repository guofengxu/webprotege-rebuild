package wprebuild.kernel.change;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLAxiom;

/**
 * Drops axiom additions and removals that cancel each other (BS-01).
 *
 * <p>The key is {@code (OntologyKey, OWLAxiom)}. One add and one remove of the same key cancel,
 * independent of order. Duplicate adds or removes of a key do not stack: the first occurrence is
 * kept at its original position. Annotation, import, and ontology-registration changes are kept.
 */
public final class ChangeListMinimiser {

    private ChangeListMinimiser() {}

    public static List<OntologyChange> minimise(List<OntologyChange> changes) {
        Objects.requireNonNull(changes, "changes");
        Set<AxiomKey> additions = new HashSet<>();
        Set<AxiomKey> removals = new HashSet<>();
        for (OntologyChange change : changes) {
            tally(change, additions, removals);
        }
        List<OntologyChange> minimised = new ArrayList<>();
        for (OntologyChange change : changes) {
            keep(change, additions, removals, minimised);
        }
        return List.copyOf(minimised);
    }

    private static void tally(OntologyChange change, Set<AxiomKey> additions, Set<AxiomKey> removals) {
        switch (requireChange(change)) {
            case AddAxiom add -> record(key(add.ontology(), add.owlAxiom()), additions, removals);
            case RemoveAxiom remove -> record(key(remove.ontology(), remove.owlAxiom()), removals, additions);
            case AddOntologyAnnotation ignored -> {}
            case RemoveOntologyAnnotation ignored -> {}
            case AddImport ignored -> {}
            case RemoveImport ignored -> {}
            case DeclareOntology ignored -> {}
        }
    }

    private static void keep(
            OntologyChange change, Set<AxiomKey> additions, Set<AxiomKey> removals, List<OntologyChange> minimised) {
        switch (change) {
            case AddAxiom add -> retain(key(add.ontology(), add.owlAxiom()), additions, add, minimised);
            case RemoveAxiom remove -> retain(key(remove.ontology(), remove.owlAxiom()), removals, remove, minimised);
            case AddOntologyAnnotation annotation -> minimised.add(annotation);
            case RemoveOntologyAnnotation annotation -> minimised.add(annotation);
            case AddImport addImport -> minimised.add(addImport);
            case RemoveImport removeImport -> minimised.add(removeImport);
            case DeclareOntology declared -> minimised.add(declared);
        }
    }

    private static OntologyChange requireChange(OntologyChange change) {
        return Objects.requireNonNull(change, "change");
    }

    /** Records {@code key} in {@code same} unless one opposite entry cancels it. */
    private static void record(AxiomKey key, Set<AxiomKey> same, Set<AxiomKey> opposite) {
        if (!opposite.remove(key)) {
            same.add(key);
        }
    }

    private static void retain(AxiomKey key, Set<AxiomKey> remaining, OntologyChange change, List<OntologyChange> out) {
        if (remaining.remove(key)) {
            out.add(change);
        }
    }

    private static AxiomKey key(OntologyKey ontology, OWLAxiom axiom) {
        return new AxiomKey(ontology, axiom);
    }

    private record AxiomKey(OntologyKey ontology, OWLAxiom axiom) {}
}
