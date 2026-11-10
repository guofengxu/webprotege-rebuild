package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalDataPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/** Writes the axiom difference between two data-property frames. */
public final class UpdateDataPropertyFrameGenerator extends AbstractFrameUpdateGenerator<DataPropertyFrame> {

    private final PropertyFrameQuery frames;

    public UpdateDataPropertyFrameGenerator(
            DataPropertyFrame from,
            DataPropertyFrame to,
            PropertyFrameQuery frames,
            ProjectRuntime runtime,
            OntologyKey targetOntology) {
        super(from, to, runtime, targetOntology, "data property");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    protected DataPropertyFrame currentFrame() {
        return frames.dataFrame(runtime(), IRI.create(from().subject().iri()));
    }

    @Override
    protected Set<OWLAxiom> toAxioms(DataPropertyFrame frame) {
        return PropertyFrameAxioms.toAxioms(frame);
    }

    @Override
    protected String label(DataPropertyFrame frame) {
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
        if (axiom instanceof OWLSubDataPropertyOfAxiom) {
            return AxiomType.SUB_DATA_PROPERTY;
        }
        if (axiom instanceof OWLDataPropertyDomainAxiom) {
            return AxiomType.DATA_PROPERTY_DOMAIN;
        }
        if (axiom instanceof OWLDataPropertyRangeAxiom) {
            return AxiomType.DATA_PROPERTY_RANGE;
        }
        if (axiom instanceof OWLFunctionalDataPropertyAxiom) {
            return AxiomType.FUNCTIONAL_DATA_PROPERTY;
        }
        throw new IllegalArgumentException("frame axiom is not editable on a data property");
    }
}
