package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.DataPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads one data-property frame. Requires {@code VIEW_PROJECT}. */
public record GetDataPropertyFrame(ProjectId projectId, IRI iri) implements Command<DataPropertyFrame> {

    public GetDataPropertyFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
    }
}
