package wprebuild.app.integration;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Stored project webhook. The signing secret is kept in the database for HMAC (P6-21) but is never
 * exposed on this type or in API responses — callers only see {@link #hasSecret()}. Logs must not
 * print the secret or the full payload URL (host only).
 */
public record Webhook(
        UUID webhookId,
        ProjectId projectId,
        URI payloadUrl,
        Set<WebhookEventType> eventTypes,
        boolean hasSecret,
        boolean enabled,
        UserId createdBy,
        Instant createdAt,
        Instant updatedAt,
        DeliverySummary lastDelivery) {

    public Webhook {
        Objects.requireNonNull(webhookId, "webhook id");
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(payloadUrl, "payload url");
        Objects.requireNonNull(eventTypes, "event types");
        eventTypes = Set.copyOf(eventTypes);
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("event types must not be empty");
        }
        Objects.requireNonNull(createdBy, "created by");
        Objects.requireNonNull(createdAt, "created at");
        Objects.requireNonNull(updatedAt, "updated at");
    }
}
