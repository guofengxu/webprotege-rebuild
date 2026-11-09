package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.id.ProjectId;

/** Hierarchy roots, ordered by display name. Requires {@code VIEW_PROJECT}. */
public record GetHierarchyRoots(ProjectId projectId, HierarchyKind kind) implements Command<List<EntityNode>> {

    public GetHierarchyRoots {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(kind, "kind");
    }
}
