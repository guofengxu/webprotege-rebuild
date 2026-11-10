package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;

/**
 * The seven object-property characteristics edited from an object-property frame.
 *
 * <p>Each value is one unary characteristic axiom. Property chains, disjoint properties, and
 * equivalent properties stay in {@code otherAxioms}.
 */
public enum ObjectPropertyCharacteristic {
    FUNCTIONAL,
    INVERSE_FUNCTIONAL,
    TRANSITIVE,
    SYMMETRIC,
    ASYMMETRIC,
    REFLEXIVE,
    IRREFLEXIVE;

    /** The axiom that asserts this characteristic of {@code property}. */
    public OWLAxiom axiom(OWLDataFactory data, OWLObjectProperty property) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(property, "property");
        return switch (this) {
            case FUNCTIONAL -> data.getOWLFunctionalObjectPropertyAxiom(property);
            case INVERSE_FUNCTIONAL -> data.getOWLInverseFunctionalObjectPropertyAxiom(property);
            case TRANSITIVE -> data.getOWLTransitiveObjectPropertyAxiom(property);
            case SYMMETRIC -> data.getOWLSymmetricObjectPropertyAxiom(property);
            case ASYMMETRIC -> data.getOWLAsymmetricObjectPropertyAxiom(property);
            case REFLEXIVE -> data.getOWLReflexiveObjectPropertyAxiom(property);
            case IRREFLEXIVE -> data.getOWLIrreflexiveObjectPropertyAxiom(property);
        };
    }
}
