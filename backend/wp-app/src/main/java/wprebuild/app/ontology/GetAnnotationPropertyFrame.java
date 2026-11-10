package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.AnnotationPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads one annotation-property frame. Requires {@code VIEW_PROJECT}. */
public record GetAnnotationPropertyFrame(ProjectId projectId, IRI iri) implements Command<AnnotationPropertyFrame> {

    public GetAnnotationPropertyFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
    }
}
