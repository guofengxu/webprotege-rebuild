package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/** Writes the axiom difference between two named-individual frames. */
public final class UpdateNamedIndividualFrameGenerator extends AbstractFrameUpdateGenerator<NamedIndividualFrame> {

    private final IndividualFrameQuery frames;

    public UpdateNamedIndividualFrameGenerator(
            NamedIndividualFrame from,
            NamedIndividualFrame to,
            IndividualFrameQuery frames,
            ProjectRuntime runtime,
            OntologyKey targetOntology) {
        super(from, to, runtime, targetOntology, "named individual");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    protected NamedIndividualFrame currentFrame() {
        return frames.frame(runtime(), IRI.create(from().subject().iri()));
    }

    @Override
    protected Set<OWLAxiom> toAxioms(NamedIndividualFrame frame) {
        return IndividualFrameAxioms.toAxioms(frame);
    }

    @Override
    protected String label(NamedIndividualFrame frame) {
        return frame.subject().displayName();
    }

    @Override
    protected Stream<OWLAxiom> candidates(ProjectOntologies ontologies, OWLAxiom rebuilt) {
        return switch (rebuilt) {
            case OWLAnnotationAssertionAxiom assertion
            when assertion.getSubject() instanceof IRI subject ->
                ontologies.annotationAssertions(subject).map(OWLAxiom.class::cast);
            case OWLClassAssertionAxiom ignored -> ofType(ontologies, AxiomType.CLASS_ASSERTION);
            case OWLObjectPropertyAssertionAxiom ignored -> ofType(ontologies, AxiomType.OBJECT_PROPERTY_ASSERTION);
            case OWLDataPropertyAssertionAxiom ignored -> ofType(ontologies, AxiomType.DATA_PROPERTY_ASSERTION);
            case OWLSameIndividualAxiom ignored -> ofType(ontologies, AxiomType.SAME_INDIVIDUAL);
            default -> throw new IllegalArgumentException("frame axiom is not editable on a named individual");
        };
    }
}
