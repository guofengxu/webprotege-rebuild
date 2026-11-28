package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;
import wprebuild.kernel.id.ProjectId;

/** One {@code project_form} row. {@code descriptorJson} is the FormDescriptor Jackson tree. */
public record FormDescriptorRow(ProjectId projectId, UUID formId, int ordinal, String descriptorJson) {

    public FormDescriptorRow {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(descriptorJson, "descriptor json");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
    }
}
