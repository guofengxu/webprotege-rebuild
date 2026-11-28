package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;
import wprebuild.kernel.id.ProjectId;

/** One {@code project_form_selector} row. {@code criteriaJson} is the criteria Jackson tree. */
public record FormSelectorRow(ProjectId projectId, UUID formId, String criteriaJson) {

    public FormSelectorRow {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(criteriaJson, "criteria json");
    }
}
