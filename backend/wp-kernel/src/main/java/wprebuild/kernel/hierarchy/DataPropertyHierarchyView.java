package wprebuild.kernel.hierarchy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Asserted data-property hierarchy (BS-08).
 *
 * <p>{@code owl:topDataProperty} is the only root. A property with no named super property hangs
 * under that root.
 */
public final class DataPropertyHierarchyView extends AbstractHierarchyView<OWLDataProperty> {

    public DataPropertyHierarchyView() {
        super(OWLManager.getOWLDataFactory().getOWLTopDataProperty());
    }

    @Override
    protected boolean isKind(OWLEntity entity) {
        return entity.isOWLDataProperty();
    }

    @Override
    protected OWLDataProperty asKind(OWLEntity entity) {
        return entity.asOWLDataProperty();
    }

    @Override
    protected Set<OWLDataProperty> namedParents(ProjectOntologies ontologies, OWLDataProperty property) {
        Set<OWLDataProperty> named = new LinkedHashSet<>();
        for (OWLAxiom axiom : ontologies.referencingAxioms(property).toList()) {
            collectNamedParent(axiom, property, named);
        }
        return named;
    }

    private static void collectNamedParent(OWLAxiom axiom, OWLDataProperty property, Set<OWLDataProperty> named) {
        switch (axiom) {
            case OWLSubDataPropertyOfAxiom sub
            when sub.getSubProperty().equals(property) -> {
                if (sub.getSuperProperty() instanceof OWLDataProperty parent) {
                    named.add(parent);
                }
            }
            default -> {
                // BS-08: only a named SubDataPropertyOf super property is a parent.
            }
        }
    }
}
