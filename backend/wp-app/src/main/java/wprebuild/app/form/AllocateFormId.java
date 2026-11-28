package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Allocates a fresh form id (UUID). Requires {@code EDIT_FORMS}. */
public record AllocateFormId(ProjectId projectId) implements Command<UUID> {

    public AllocateFormId {
        Objects.requireNonNull(projectId, "project id");
    }
}
