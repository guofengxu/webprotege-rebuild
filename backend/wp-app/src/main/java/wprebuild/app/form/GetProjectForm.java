package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Loads one form descriptor and its selector. Requires {@code EDIT_FORMS}. */
public record GetProjectForm(ProjectId projectId, UUID formId) implements Command<ProjectForm> {

    public GetProjectForm {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
    }
}
