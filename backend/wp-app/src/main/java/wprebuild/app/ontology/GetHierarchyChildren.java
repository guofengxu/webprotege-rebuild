package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.id.ProjectId;

/** Direct children of one node, ordered by display name. Requires {@code VIEW_PROJECT}. */
public record GetHierarchyChildren(ProjectId projectId, HierarchyKind kind, IRI parent)
        implements Command<List<EntityNode>> {

    public GetHierarchyChildren {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(parent, "parent");
    }
}
