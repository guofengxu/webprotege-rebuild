package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAsymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLInverseFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLIrreflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLReflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/** Writes the axiom difference between two object-property frames. */
public final class UpdateObjectPropertyFrameGenerator extends AbstractFrameUpdateGenerator<ObjectPropertyFrame> {

    private final PropertyFrameQuery frames;

    public UpdateObjectPropertyFrameGenerator(
            ObjectPropertyFrame from,
            ObjectPropertyFrame to,
            PropertyFrameQuery frames,
            ProjectRuntime runtime,
            OntologyKey targetOntology) {
        super(from, to, runtime, targetOntology, "object property");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    protected ObjectPropertyFrame currentFrame() {
        return frames.objectFrame(runtime(), IRI.create(from().subject().iri()));
    }

    @Override
    protected Set<OWLAxiom> toAxioms(ObjectPropertyFrame frame) {
        return PropertyFrameAxioms.toAxioms(frame);
    }

    @Override
    protected String label(ObjectPropertyFrame frame) {
        return frame.subject().displayName();
    }

    @Override
    protected Stream<OWLAxiom> candidates(ProjectOntologies ontologies, OWLAxiom rebuilt) {
        if (rebuilt instanceof OWLAnnotationAssertionAxiom assertion && assertion.getSubject() instanceof IRI subject) {
            return ontologies.annotationAssertions(subject).map(OWLAxiom.class::cast);
        }
        return ofType(ontologies, type(rebuilt));
    }

    private static AxiomType<?> type(OWLAxiom axiom) {
        if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
            return AxiomType.SUB_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLObjectPropertyDomainAxiom) {
            return AxiomType.OBJECT_PROPERTY_DOMAIN;
        }
        if (axiom instanceof OWLObjectPropertyRangeAxiom) {
            return AxiomType.OBJECT_PROPERTY_RANGE;
        }
        if (axiom instanceof OWLFunctionalObjectPropertyAxiom) {
            return AxiomType.FUNCTIONAL_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLInverseFunctionalObjectPropertyAxiom) {
            return AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLTransitiveObjectPropertyAxiom) {
            return AxiomType.TRANSITIVE_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLSymmetricObjectPropertyAxiom) {
            return AxiomType.SYMMETRIC_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLAsymmetricObjectPropertyAxiom) {
            return AxiomType.ASYMMETRIC_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLReflexiveObjectPropertyAxiom) {
            return AxiomType.REFLEXIVE_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLIrreflexiveObjectPropertyAxiom) {
            return AxiomType.IRREFLEXIVE_OBJECT_PROPERTY;
        }
        if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            return AxiomType.INVERSE_OBJECT_PROPERTIES;
        }
        throw new IllegalArgumentException("frame axiom is not editable on an object property");
    }
}
