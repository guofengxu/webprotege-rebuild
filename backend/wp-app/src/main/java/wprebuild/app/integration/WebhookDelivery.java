package wprebuild.app.integration;

import java.time.Instant;
import java.util.Objects;

/** Read-only delivery row without the JSON payload. */
public record WebhookDelivery(
        long deliveryId,
        String status,
        int attempts,
        Integer lastStatusCode,
        String lastError,
        Instant createdAt,
        Instant finishedAt) {

    public WebhookDelivery {
        if (deliveryId < 1) {
            throw new IllegalArgumentException("delivery id must be positive");
        }
        Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0");
        }
        Objects.requireNonNull(createdAt, "created at");
    }
}
