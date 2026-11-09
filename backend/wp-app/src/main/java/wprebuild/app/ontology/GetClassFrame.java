package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.ClassFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads one class frame. Requires {@code VIEW_PROJECT}. */
public record GetClassFrame(ProjectId projectId, IRI iri) implements Command<ClassFrame> {

    public GetClassFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
    }
}
