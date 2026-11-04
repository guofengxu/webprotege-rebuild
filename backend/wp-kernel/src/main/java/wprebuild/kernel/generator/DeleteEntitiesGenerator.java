package wprebuild.kernel.generator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Removes every mention of the given entities from every ontology in the project (BS-07).
 *
 * <p>{@link ProjectOntologies#referencingAxioms} follows the axiom signature, which does not include
 * an annotation-assertion subject. The legacy {@code EntityDeleter} also removes annotation axioms
 * whose subject, value, domain, or range is the entity IRI, plus ontology annotations that point at
 * the entity. Annotations hanging off a logical axiom are not IRI references there, so those axioms
 * stay unless the entity is in the signature.
 */
public final class DeleteEntitiesGenerator implements ChangeListGenerator<Set<OWLEntity>> {

    private final Set<OWLEntity> entities;

    public DeleteEntitiesGenerator(Set<OWLEntity> entities) {
        this.entities = copy(entities);
    }

    @Override
    public GeneratedChanges<Set<OWLEntity>> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        if (entities.isEmpty()) {
            return new GeneratedChanges<>(List.of(), Set.of());
        }
        ProjectOntologies ontologies = context.ontologies();
        Set<IRI> iris = entityIris();
        List<OntologyChange> changes = new ArrayList<>();
        for (OntologyKey key : sorted(ontologies.keys())) {
            removeAxioms(ontologies, key, iris, changes);
            removeAnnotations(ontologies, key, iris, changes);
        }
        return new GeneratedChanges<>(changes, entities);
    }

    @Override
    public String describe(GeneratedChanges<Set<OWLEntity>> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        int count = entities.size();
        return "Deleted " + count + " " + (count == 1 ? "entity" : "entities");
    }

    private void removeAxioms(
            ProjectOntologies ontologies, OntologyKey key, Set<IRI> iris, List<OntologyChange> changes) {
        Set<OWLAxiom> doomed = new LinkedHashSet<>();
        ontologies.axioms(key).filter(axiom -> references(axiom, iris)).forEach(doomed::add);
        for (OWLAxiom axiom : doomed) {
            changes.add(new RemoveAxiom(key, axiom));
        }
    }

    private void removeAnnotations(
            ProjectOntologies ontologies, OntologyKey key, Set<IRI> iris, List<OntologyChange> changes) {
        for (OWLAnnotation annotation : ontologies.annotations(key)) {
            if (annotationsMention(List.of(annotation), iris)) {
                changes.add(new RemoveOntologyAnnotation(key, annotation));
            }
        }
    }

    private boolean references(OWLAxiom axiom, Set<IRI> iris) {
        if (signatureHits(axiom)) {
            return true;
        }
        if (!(axiom instanceof OWLAnnotationAxiom annotationAxiom)) {
            return false;
        }
        if (iriPosition(annotationAxiom, iris)) {
            return true;
        }
        return annotationsMention(annotationAxiom.getAnnotations(), iris);
    }

    private boolean signatureHits(OWLAxiom axiom) {
        for (OWLEntity signed : axiom.getSignature()) {
            if (entities.contains(signed)) {
                return true;
            }
        }
        return false;
    }

    private Set<IRI> entityIris() {
        Set<IRI> iris = new LinkedHashSet<>();
        for (OWLEntity entity : entities) {
            iris.add(entity.getIRI());
        }
        return Set.copyOf(iris);
    }

    private boolean annotationsMention(Collection<OWLAnnotation> annotations, Set<IRI> iris) {
        for (OWLAnnotation annotation : annotations) {
            if (entities.contains(annotation.getProperty())
                    || iriValue(annotation.getValue(), iris)
                    || annotationsMention(annotation.getAnnotations(), iris)) {
                return true;
            }
        }
        return false;
    }

    private static boolean iriPosition(OWLAnnotationAxiom axiom, Set<IRI> iris) {
        if (axiom instanceof OWLAnnotationAssertionAxiom assertion) {
            if (assertion.getSubject() instanceof IRI subject && iris.contains(subject)) {
                return true;
            }
            return iriValue(assertion.getValue(), iris);
        }
        if (axiom instanceof OWLAnnotationPropertyDomainAxiom domain) {
            return iris.contains(domain.getDomain());
        }
        if (axiom instanceof OWLAnnotationPropertyRangeAxiom range) {
            return iris.contains(range.getRange());
        }
        return false;
    }

    private static boolean iriValue(OWLAnnotationValue value, Set<IRI> iris) {
        return value instanceof IRI iri && iris.contains(iri);
    }

    private static List<OntologyKey> sorted(Set<OntologyKey> keys) {
        List<OntologyKey> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparing(OntologyKey::value));
        return ordered;
    }

    private static Set<OWLEntity> copy(Set<OWLEntity> entities) {
        Objects.requireNonNull(entities, "entities");
        Set<OWLEntity> copied = new LinkedHashSet<>();
        for (OWLEntity entity : entities) {
            copied.add(Objects.requireNonNull(entity, "entity"));
        }
        return Set.copyOf(copied);
    }
}
