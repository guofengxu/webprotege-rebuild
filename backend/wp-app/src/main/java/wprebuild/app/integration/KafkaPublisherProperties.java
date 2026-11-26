package wprebuild.app.integration;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import wprebuild.app.revision.OutboxConsumer;

/**
 * {@code wp.kafka.*} settings for the optional outbox → Kafka publisher (P6-35). Broker connection
 * uses standard {@code spring.kafka.*}.
 */
@ConfigurationProperties(prefix = "wp.kafka")
public record KafkaPublisherProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("wp.revisions") String topic,
        @DefaultValue("LATEST") StartFrom startFrom,
        @DefaultValue("10s") Duration sendTimeout) {

    public KafkaPublisherProperties {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(startFrom, "start from");
        Objects.requireNonNull(sendTimeout, "send timeout");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("wp.kafka.topic must not be blank");
        }
        if (sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("wp.kafka.send-timeout must be positive");
        }
    }

    /** Maps to {@link OutboxConsumer#startFrom()} on first registration. */
    public OutboxConsumer.StartPosition consumerStartPosition() {
        return switch (startFrom) {
            case LATEST -> OutboxConsumer.StartPosition.LATEST;
            case EARLIEST -> OutboxConsumer.StartPosition.EARLIEST;
        };
    }

    /** Where a brand-new {@code kafka} outbox consumer row starts. */
    public enum StartFrom {
        LATEST,
        EARLIEST
    }
}
