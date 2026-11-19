package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Lists ontologies in a project under the read lock. Requires {@code VIEW_PROJECT}. */
public record ListProjectOntologies(ProjectId projectId) implements Command<List<ProjectOntologySummary>> {

    public ListProjectOntologies {
        Objects.requireNonNull(projectId, "project id");
    }
}
