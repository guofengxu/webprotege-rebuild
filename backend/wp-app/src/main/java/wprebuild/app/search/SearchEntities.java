package wprebuild.app.search;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.id.ProjectId;

/**
 * Finds entities by display name or local name. An empty {@code types} set searches every type.
 * Requires {@code VIEW_PROJECT}.
 */
public record SearchEntities(ProjectId projectId, String query, Set<EntityType<?>> types, int limit)
        implements Command<List<EntityNode>> {

    /** Same cap as an individual page, so one query cannot ask Lucene for an unbounded hit list. */
    public static final int MAX_LIMIT = 200;

    public SearchEntities {
        Objects.requireNonNull(projectId, "project id");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        query = query.strip();
        types = Set.copyOf(Objects.requireNonNull(types, "types"));
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
        }
    }
}
