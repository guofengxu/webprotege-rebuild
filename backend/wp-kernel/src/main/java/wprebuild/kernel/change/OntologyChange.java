package wprebuild.kernel.change;

import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;

/**
 * One immutable edit of a project ontology, addressed by {@link OntologyKey} rather than
 * {@code OWLOntologyID}.
 */
public sealed interface OntologyChange
        permits AddAxiom,
                RemoveAxiom,
                AddOntologyAnnotation,
                RemoveOntologyAnnotation,
                AddImport,
                RemoveImport,
                DeclareOntology {

    OntologyKey ontology();

    default OntologyChange inverse() {
        return switch (this) {
            case AddAxiom change -> new RemoveAxiom(change.ontology(), change.owlAxiom());
            case RemoveAxiom change -> new AddAxiom(change.ontology(), change.owlAxiom());
            case AddOntologyAnnotation change -> new RemoveOntologyAnnotation(change.ontology(), change.annotation());
            case RemoveOntologyAnnotation change -> new AddOntologyAnnotation(change.ontology(), change.annotation());
            case AddImport change -> new RemoveImport(change.ontology(), change.iri());
            case RemoveImport change -> new AddImport(change.ontology(), change.iri());
            case DeclareOntology ignored ->
                throw new UnsupportedOperationException("DeclareOntology cannot be inverted (BS-04)");
        };
    }

    default Set<OWLEntity> signature() {
        return switch (this) {
            case AddAxiom change -> Set.copyOf(change.owlAxiom().getSignature());
            case RemoveAxiom change -> Set.copyOf(change.owlAxiom().getSignature());
            case AddOntologyAnnotation change -> Set.copyOf(change.annotation().getSignature());
            case RemoveOntologyAnnotation change ->
                Set.copyOf(change.annotation().getSignature());
            case AddImport ignored -> Set.of();
            case RemoveImport ignored -> Set.of();
            case DeclareOntology ignored -> Set.of();
        };
    }

    default boolean isAxiomChange() {
        return switch (this) {
            case AddAxiom ignored -> true;
            case RemoveAxiom ignored -> true;
            case AddOntologyAnnotation ignored -> false;
            case RemoveOntologyAnnotation ignored -> false;
            case AddImport ignored -> false;
            case RemoveImport ignored -> false;
            case DeclareOntology ignored -> false;
        };
    }

    default Optional<OWLAxiom> axiom() {
        return switch (this) {
            case AddAxiom change -> Optional.of(change.owlAxiom());
            case RemoveAxiom change -> Optional.of(change.owlAxiom());
            case AddOntologyAnnotation ignored -> Optional.empty();
            case RemoveOntologyAnnotation ignored -> Optional.empty();
            case AddImport ignored -> Optional.empty();
            case RemoveImport ignored -> Optional.empty();
            case DeclareOntology ignored -> Optional.empty();
        };
    }
}
