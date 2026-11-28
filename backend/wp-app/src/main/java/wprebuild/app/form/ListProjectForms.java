package wprebuild.app.form;

import java.util.List;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Lists form descriptors and selectors for a project. Requires {@code EDIT_FORMS}. */
public record ListProjectForms(ProjectId projectId) implements Command<List<ProjectForm>> {

    public ListProjectForms {
        Objects.requireNonNull(projectId, "project id");
    }
}
