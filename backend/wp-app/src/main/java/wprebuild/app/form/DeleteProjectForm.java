package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Deletes a form descriptor and its selector. Requires {@code EDIT_FORMS}. */
public record DeleteProjectForm(ProjectId projectId, UUID formId) implements Command<Void> {

    public DeleteProjectForm {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
    }
}
