package wprebuild.app.ontology;

import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.project.ProjectText;
import wprebuild.kernel.id.ProjectId;

/** Creates a project whose first revision declares one ontology. Requires {@code CREATE_EMPTY_PROJECT}. */
public record CreateEmptyProject(String name, String description, Optional<IRI> ontologyIri)
        implements Command<ProjectId> {

    public CreateEmptyProject {
        name = ProjectText.displayName(name);
        description = ProjectText.description(description);
        if (ontologyIri == null) {
            throw new IllegalArgumentException("ontology IRI must not be null");
        }
        ontologyIri.ifPresent(iri -> {
            if (!iri.isAbsolute()) {
                throw new IllegalArgumentException("ontology IRI must be absolute");
            }
        });
    }
}
