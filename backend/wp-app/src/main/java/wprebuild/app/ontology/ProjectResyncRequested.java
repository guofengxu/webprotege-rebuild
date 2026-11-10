package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;

/** Asks the events module to tell clients to reload one project. */
public record ProjectResyncRequested(ProjectId projectId) {

    public ProjectResyncRequested {
        Objects.requireNonNull(projectId, "project id");
    }
}
