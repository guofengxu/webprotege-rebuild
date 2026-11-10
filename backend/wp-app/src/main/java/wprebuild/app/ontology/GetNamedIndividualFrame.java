package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.NamedIndividualFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads one named-individual frame. Requires {@code VIEW_PROJECT}. */
public record GetNamedIndividualFrame(ProjectId projectId, IRI iri) implements Command<NamedIndividualFrame> {

    public GetNamedIndividualFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
    }
}
