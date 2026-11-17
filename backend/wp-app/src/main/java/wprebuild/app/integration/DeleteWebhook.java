package wprebuild.app.integration;

import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Deletes a webhook (and cascaded deliveries). Requires {@code EDIT_PROJECT_SETTINGS}. */
public record DeleteWebhook(ProjectId projectId, UUID webhookId) implements Command<Void> {

    public DeleteWebhook {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(webhookId, "webhook id");
    }
}
