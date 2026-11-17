package wprebuild.app.integration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Recent deliveries for one webhook ({@code limit} 1–50). Requires {@code EDIT_PROJECT_SETTINGS}.
 */
public record ListWebhookDeliveries(ProjectId projectId, UUID webhookId, int limit)
        implements Command<List<WebhookDelivery>> {

    public ListWebhookDeliveries {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(webhookId, "webhook id");
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
    }
}
