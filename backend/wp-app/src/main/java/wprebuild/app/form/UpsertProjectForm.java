package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Creates or updates a form descriptor and replaces its selector. A null {@code criteriaJson}
 * clears any existing selector. Requires {@code EDIT_FORMS}.
 */
public record UpsertProjectForm(ProjectId projectId, UUID formId, String descriptorJson, String criteriaJson)
        implements Command<ProjectForm> {

    public UpsertProjectForm {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(descriptorJson, "descriptor json");
    }
}
