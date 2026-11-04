package wprebuild.kernel.hierarchy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Asserted class hierarchy (BS-08).
 *
 * <p>{@code owl:Thing} is always the only root, even when a subclass axiom names it as the
 * subclass. A class with no named parent hangs under {@code owl:Thing}.
 */
public final class ClassHierarchyView extends AbstractHierarchyView<OWLClass> {

    public ClassHierarchyView() {
        super(OWLManager.getOWLDataFactory().getOWLThing());
    }

    @Override
    protected boolean isKind(OWLEntity entity) {
        return entity.isOWLClass();
    }

    @Override
    protected OWLClass asKind(OWLEntity entity) {
        return entity.asOWLClass();
    }

    /**
     * Named parents from {@code SubClassOf(C P)} and from named operands of an intersection that
     * is equivalent to {@code C}. An empty result means {@code owl:Thing} (BS-08).
     */
    @Override
    protected Set<OWLClass> namedParents(ProjectOntologies ontologies, OWLClass cls) {
        Set<OWLClass> named = new LinkedHashSet<>();
        for (OWLAxiom axiom : ontologies.referencingAxioms(cls).toList()) {
            collectNamedParents(axiom, cls, named);
        }
        return named;
    }

    private static void collectNamedParents(OWLAxiom axiom, OWLClass cls, Set<OWLClass> named) {
        switch (axiom) {
            case OWLSubClassOfAxiom sub
            when sub.getSubClass().equals(cls) -> {
                if (sub.getSuperClass() instanceof OWLClass parent) {
                    named.add(parent);
                }
            }
            case OWLEquivalentClassesAxiom equivalent -> addIntersectionParents(equivalent, cls, named);
            default -> {
                // Other axioms do not assert a named class parent (BS-08).
            }
        }
    }

    private static void addIntersectionParents(OWLEquivalentClassesAxiom axiom, OWLClass cls, Set<OWLClass> named) {
        boolean subject = false;
        Set<OWLClass> conjuncts = new LinkedHashSet<>();
        for (OWLClassExpression expression : axiom.getClassExpressions()) {
            if (expression.equals(cls)) {
                subject = true;
            } else if (expression instanceof OWLObjectIntersectionOf intersection) {
                addNamedOperands(intersection, conjuncts);
            }
        }
        if (subject) {
            named.addAll(conjuncts);
        }
    }

    private static void addNamedOperands(OWLObjectIntersectionOf intersection, Set<OWLClass> named) {
        for (OWLClassExpression operand : intersection.getOperands()) {
            if (operand instanceof OWLClass parent) {
                named.add(parent);
            }
        }
    }
}
