package wprebuild.app.integration;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Updates a webhook. Disabling cancels pending deliveries in the same transaction. Requires {@code
 * EDIT_PROJECT_SETTINGS}.
 */
public record UpdateWebhook(
        ProjectId projectId,
        UUID webhookId,
        String payloadUrl,
        Set<WebhookEventType> eventTypes,
        SecretChange secretChange,
        boolean enabled)
        implements Command<Webhook> {

    public UpdateWebhook {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(webhookId, "webhook id");
        Objects.requireNonNull(payloadUrl, "payload url");
        Objects.requireNonNull(eventTypes, "event types");
        eventTypes = Set.copyOf(eventTypes);
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("event types must not be empty");
        }
        Objects.requireNonNull(secretChange, "secret change");
    }
}
