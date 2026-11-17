package wprebuild.app.integration;

import java.util.Objects;
import java.util.Set;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Registers a webhook. {@code secret} may be null; when present it must be at least 16 characters.
 * Requires {@code EDIT_PROJECT_SETTINGS}.
 */
public record CreateWebhook(
        ProjectId projectId, String payloadUrl, Set<WebhookEventType> eventTypes, String secret, boolean enabled)
        implements Command<Webhook> {

    public CreateWebhook {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(payloadUrl, "payload url");
        Objects.requireNonNull(eventTypes, "event types");
        eventTypes = Set.copyOf(eventTypes);
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("event types must not be empty");
        }
        if (secret != null && secret.length() < 16) {
            throw new IllegalArgumentException("secret must be at least 16 characters");
        }
    }
}
