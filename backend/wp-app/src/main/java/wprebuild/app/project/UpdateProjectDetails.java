package wprebuild.app.project;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Renames a project and, when {@code description} is present, replaces the description. A null
 * description leaves the stored text. The bus requires {@code EDIT_PROJECT_SETTINGS}.
 */
public record UpdateProjectDetails(ProjectId projectId, String name, String description)
        implements Command<ProjectDetails> {

    public UpdateProjectDetails {
        Objects.requireNonNull(projectId, "project id");
        name = ProjectText.displayName(name);
        if (description != null) {
            description = ProjectText.description(description);
        }
    }
}
