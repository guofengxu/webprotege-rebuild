package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;
import wprebuild.kernel.id.ProjectId;

/**
 * Descriptor plus optional selector for one form (BS-16). {@code criteriaJson} is null when the
 * form has no selector.
 */
public record ProjectForm(ProjectId projectId, UUID formId, int ordinal, String descriptorJson, String criteriaJson) {

    public ProjectForm {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(descriptorJson, "descriptor json");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
    }
}
