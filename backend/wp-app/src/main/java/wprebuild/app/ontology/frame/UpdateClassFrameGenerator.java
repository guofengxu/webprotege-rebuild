package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/** Writes the axiom difference between two class frames. */
public final class UpdateClassFrameGenerator extends AbstractFrameUpdateGenerator<ClassFrame> {

    private final ClassFrameQuery frames;

    public UpdateClassFrameGenerator(
            ClassFrame from,
            ClassFrame to,
            ClassFrameQuery frames,
            ProjectRuntime runtime,
            OntologyKey targetOntology) {
        super(from, to, runtime, targetOntology, "class");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    protected ClassFrame currentFrame() {
        return frames.frame(runtime(), IRI.create(from().subject().iri()));
    }

    @Override
    protected Set<OWLAxiom> toAxioms(ClassFrame frame) {
        return ClassFrameAxioms.toAxioms(frame);
    }

    @Override
    protected String label(ClassFrame frame) {
        return frame.subject().displayName();
    }

    @Override
    protected Stream<OWLAxiom> candidates(ProjectOntologies ontologies, OWLAxiom rebuilt) {
        return switch (rebuilt) {
            case OWLAnnotationAssertionAxiom assertion
            when assertion.getSubject() instanceof IRI subject ->
                ontologies.annotationAssertions(subject).map(OWLAxiom.class::cast);
            case OWLSubClassOfAxiom ignored -> ofType(ontologies, AxiomType.SUBCLASS_OF);
            default -> throw new IllegalArgumentException("frame axiom must be an annotation or subclass axiom");
        };
    }
}
