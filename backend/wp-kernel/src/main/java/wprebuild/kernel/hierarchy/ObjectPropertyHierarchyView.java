package wprebuild.kernel.hierarchy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Asserted object-property hierarchy (BS-08).
 *
 * <p>{@code owl:topObjectProperty} is the only root. A property with no named super property hangs
 * under that root. Inverses and property chains do not add parent edges.
 */
public final class ObjectPropertyHierarchyView extends AbstractHierarchyView<OWLObjectProperty> {

    public ObjectPropertyHierarchyView() {
        super(OWLManager.getOWLDataFactory().getOWLTopObjectProperty());
    }

    @Override
    protected boolean isKind(OWLEntity entity) {
        return entity.isOWLObjectProperty();
    }

    @Override
    protected OWLObjectProperty asKind(OWLEntity entity) {
        return entity.asOWLObjectProperty();
    }

    @Override
    protected Set<OWLObjectProperty> namedParents(ProjectOntologies ontologies, OWLObjectProperty property) {
        Set<OWLObjectProperty> named = new LinkedHashSet<>();
        for (OWLAxiom axiom : ontologies.referencingAxioms(property).toList()) {
            collectNamedParent(axiom, property, named);
        }
        return named;
    }

    private static void collectNamedParent(OWLAxiom axiom, OWLObjectProperty property, Set<OWLObjectProperty> named) {
        switch (axiom) {
            case OWLSubObjectPropertyOfAxiom sub
            when sub.getSubProperty().equals(property) -> {
                if (sub.getSuperProperty() instanceof OWLObjectProperty parent) {
                    named.add(parent);
                }
            }
            default -> {
                // BS-08: only a named SubObjectPropertyOf super property is a parent.
            }
        }
    }
}
