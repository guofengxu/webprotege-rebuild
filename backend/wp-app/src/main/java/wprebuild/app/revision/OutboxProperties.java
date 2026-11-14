package wprebuild.app.revision;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wp.outbox.*} settings for the in-process outbox relay. */
@ConfigurationProperties(prefix = "wp.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("200") int batchSize) {

    public OutboxProperties {
        Objects.requireNonNull(pollInterval, "poll interval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("wp.outbox.poll-interval must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("wp.outbox.batch-size must be positive");
        }
    }
}
