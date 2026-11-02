package wprebuild.kernel.ontology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.ChangeListMinimiser;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;

/**
 * Keeps changes that alter the project ontologies (BS-02).
 *
 * <p>The list is minimised first (BS-01). Each remaining change is judged against the membership
 * produced by earlier effective changes in the same list, and {@code current} is left unchanged.
 * {@link DeclareOntology} is effective when its key is not registered yet. Any other change whose
 * key is still unregistered throws {@link IllegalArgumentException}.
 *
 * <p>Import changes use the same contains / does-not-contain rule. The legacy filter dropped every
 * import because its visitor returned null (BS-02).
 */
public final class EffectiveChanges {

    private EffectiveChanges() {}

    public static List<OntologyChange> compute(ProjectOntologies current, List<OntologyChange> changes) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(changes, "changes");
        BatchView view = new BatchView(current);
        List<OntologyChange> effective = new ArrayList<>();
        for (OntologyChange change : ChangeListMinimiser.minimise(changes)) {
            if (view.effective(change)) {
                effective.add(change);
                view.record(change);
            }
        }
        return List.copyOf(effective);
    }

    /** Membership of one batch, layered over the untouched {@link ProjectOntologies}. */
    private static final class BatchView {

        private final ProjectOntologies current;

        private final Set<OntologyKey> initialKeys;

        private final Set<OntologyKey> knownKeys;

        private final Membership<OWLAxiom> axioms = new Membership<>();

        private final Membership<OWLAnnotation> annotations = new Membership<>();

        private final Membership<IRI> imports = new Membership<>();

        private BatchView(ProjectOntologies current) {
            this.current = current;
            this.initialKeys = Set.copyOf(current.keys());
            this.knownKeys = new HashSet<>(initialKeys);
        }

        private boolean effective(OntologyChange change) {
            return switch (change) {
                case DeclareOntology declare -> !knownKeys.contains(declare.ontology());
                case AddAxiom add -> {
                    requireRegistered(add.ontology());
                    yield !containsAxiom(add.ontology(), add.owlAxiom());
                }
                case RemoveAxiom remove -> {
                    requireRegistered(remove.ontology());
                    yield containsAxiom(remove.ontology(), remove.owlAxiom());
                }
                case AddOntologyAnnotation add -> {
                    requireRegistered(add.ontology());
                    yield !containsAnnotation(add.ontology(), add.annotation());
                }
                case RemoveOntologyAnnotation remove -> {
                    requireRegistered(remove.ontology());
                    yield containsAnnotation(remove.ontology(), remove.annotation());
                }
                case AddImport add -> {
                    requireRegistered(add.ontology());
                    yield !containsImport(add.ontology(), add.iri());
                }
                case RemoveImport remove -> {
                    requireRegistered(remove.ontology());
                    yield containsImport(remove.ontology(), remove.iri());
                }
            };
        }

        private void record(OntologyChange change) {
            switch (change) {
                case DeclareOntology declare -> knownKeys.add(declare.ontology());
                case AddAxiom add -> axioms.add(add.ontology(), add.owlAxiom());
                case RemoveAxiom remove -> axioms.remove(remove.ontology(), remove.owlAxiom());
                case AddOntologyAnnotation add -> annotations.add(add.ontology(), add.annotation());
                case RemoveOntologyAnnotation remove -> annotations.remove(remove.ontology(), remove.annotation());
                case AddImport add -> imports.add(add.ontology(), add.iri());
                case RemoveImport remove -> imports.remove(remove.ontology(), remove.iri());
            }
        }

        private boolean containsAxiom(OntologyKey key, OWLAxiom axiom) {
            return contains(axioms, key, axiom, () -> current.containsAxiom(key, axiom));
        }

        private boolean containsAnnotation(OntologyKey key, OWLAnnotation annotation) {
            return contains(annotations, key, annotation, () -> current.containsAnnotation(key, annotation));
        }

        private boolean containsImport(OntologyKey key, IRI iri) {
            return contains(imports, key, iri, () -> current.containsImport(key, iri));
        }

        private <T> boolean contains(Membership<T> membership, OntologyKey key, T value, BooleanSupplier base) {
            Optional<Boolean> overlay = membership.state(key, value);
            if (overlay.isPresent()) {
                return overlay.get();
            }
            // A key declared in this batch is absent from current. Do not query it.
            if (!initialKeys.contains(key)) {
                return false;
            }
            return base.getAsBoolean();
        }

        private void requireRegistered(OntologyKey key) {
            if (!knownKeys.contains(key)) {
                throw new IllegalArgumentException("ontology is not registered: " + key.value());
            }
        }
    }

    /** Additions and removals already accepted in this batch. */
    private static final class Membership<T> {

        private final Map<OntologyKey, Set<T>> added = new HashMap<>();

        private final Map<OntologyKey, Set<T>> removed = new HashMap<>();

        private Optional<Boolean> state(OntologyKey key, T value) {
            if (has(added, key, value)) {
                return Optional.of(true);
            }
            if (has(removed, key, value)) {
                return Optional.of(false);
            }
            return Optional.empty();
        }

        private static <T> boolean has(Map<OntologyKey, Set<T>> sets, OntologyKey key, T value) {
            Set<T> values = sets.get(key);
            return values != null && values.contains(value);
        }

        private void add(OntologyKey key, T value) {
            bucket(added, key).add(value);
            bucket(removed, key).remove(value);
        }

        private void remove(OntologyKey key, T value) {
            bucket(removed, key).add(value);
            bucket(added, key).remove(value);
        }

        private static <T> Set<T> bucket(Map<OntologyKey, Set<T>> sets, OntologyKey key) {
            return sets.computeIfAbsent(key, ignored -> new HashSet<>());
        }
    }
}
