package wprebuild.app.integration;

import java.util.Objects;
import java.util.UUID;

/** A {@code webhook_deliveries} row claimed for HTTP send (P6-21). */
public record ClaimedWebhookDelivery(
        long deliveryId,
        UUID webhookId,
        UUID claimToken,
        String payloadJson,
        int attempts,
        String payloadUrl,
        String secret) {

    public ClaimedWebhookDelivery {
        if (deliveryId < 1) {
            throw new IllegalArgumentException("delivery id must be positive");
        }
        Objects.requireNonNull(webhookId, "webhook id");
        Objects.requireNonNull(claimToken, "claim token");
        Objects.requireNonNull(payloadJson, "payload json");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0");
        }
        Objects.requireNonNull(payloadUrl, "payload url");
    }

    @Override
    public String toString() {
        return "ClaimedWebhookDelivery[deliveryId="
                + deliveryId
                + ", webhookId="
                + webhookId
                + ", claimToken="
                + claimToken
                + ", attempts="
                + attempts
                + ", hasSecret="
                + (secret != null && !secret.isBlank())
                + "]";
    }
}
