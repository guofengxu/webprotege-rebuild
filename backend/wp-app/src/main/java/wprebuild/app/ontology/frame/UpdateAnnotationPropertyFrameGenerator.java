package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/** Writes the axiom difference between two annotation-property frames. */
public final class UpdateAnnotationPropertyFrameGenerator
        extends AbstractFrameUpdateGenerator<AnnotationPropertyFrame> {

    private final PropertyFrameQuery frames;

    public UpdateAnnotationPropertyFrameGenerator(
            AnnotationPropertyFrame from,
            AnnotationPropertyFrame to,
            PropertyFrameQuery frames,
            ProjectRuntime runtime,
            OntologyKey targetOntology) {
        super(from, to, runtime, targetOntology, "annotation property");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    protected AnnotationPropertyFrame currentFrame() {
        return frames.annotationFrame(runtime(), IRI.create(from().subject().iri()));
    }

    @Override
    protected Set<OWLAxiom> toAxioms(AnnotationPropertyFrame frame) {
        return PropertyFrameAxioms.toAxioms(frame);
    }

    @Override
    protected String label(AnnotationPropertyFrame frame) {
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
        if (axiom instanceof OWLSubAnnotationPropertyOfAxiom) {
            return AxiomType.SUB_ANNOTATION_PROPERTY_OF;
        }
        if (axiom instanceof OWLAnnotationPropertyDomainAxiom) {
            return AxiomType.ANNOTATION_PROPERTY_DOMAIN;
        }
        if (axiom instanceof OWLAnnotationPropertyRangeAxiom) {
            return AxiomType.ANNOTATION_PROPERTY_RANGE;
        }
        throw new IllegalArgumentException("frame axiom is not editable on an annotation property");
    }
}
