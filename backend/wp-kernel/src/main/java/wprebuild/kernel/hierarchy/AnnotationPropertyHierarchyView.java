package wprebuild.kernel.hierarchy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Asserted annotation-property hierarchy (BS-08).
 *
 * <p>There is no universal root. {@code roots()} is the annotation properties in the project
 * signature that have no super property. Built-in properties appear only once they are referenced.
 */
public final class AnnotationPropertyHierarchyView extends AbstractHierarchyView<OWLAnnotationProperty> {

    @Override
    protected boolean isKind(OWLEntity entity) {
        return entity.isOWLAnnotationProperty();
    }

    @Override
    protected OWLAnnotationProperty asKind(OWLEntity entity) {
        return entity.asOWLAnnotationProperty();
    }

    @Override
    protected Set<OWLAnnotationProperty> namedParents(ProjectOntologies ontologies, OWLAnnotationProperty property) {
        Set<OWLAnnotationProperty> named = new LinkedHashSet<>();
        for (OWLAxiom axiom : ontologies.referencingAxioms(property).toList()) {
            collectNamedParent(axiom, property, named);
        }
        return named;
    }

    private static void collectNamedParent(
            OWLAxiom axiom, OWLAnnotationProperty property, Set<OWLAnnotationProperty> named) {
        switch (axiom) {
            case OWLSubAnnotationPropertyOfAxiom sub
            when sub.getSubProperty().equals(property) -> named.add(sub.getSuperProperty());
            default -> {
                // BS-08: only SubAnnotationPropertyOf asserts a parent.
            }
        }
    }
}
