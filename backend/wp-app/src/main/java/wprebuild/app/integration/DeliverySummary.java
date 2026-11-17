package wprebuild.app.integration;

import java.time.Instant;
import java.util.Objects;

/** Latest delivery outcome for a webhook; {@code null} fields mean the attempt has not finished. */
public record DeliverySummary(String status, Integer statusCode, Instant finishedAt, String error) {

    public DeliverySummary {
        Objects.requireNonNull(status, "status");
    }
}
