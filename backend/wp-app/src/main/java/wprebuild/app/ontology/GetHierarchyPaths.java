package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.id.ProjectId;

/**
 * Paths from a root to one entity. Each path starts at a root and ends at the entity. An unknown
 * entity yields an empty list. Requires {@code VIEW_PROJECT}.
 */
public record GetHierarchyPaths(ProjectId projectId, HierarchyKind kind, IRI entity)
        implements Command<List<List<EntityNode>>> {

    public GetHierarchyPaths {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entity, "entity");
    }
}
