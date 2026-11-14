package wprebuild.app.revision;

import java.util.List;

/**
 * SPI for outbox fans (projection tables, webhooks, Kafka, instance stores). Implementations live in
 * upper modules; this package only depends on {@code shared}.
 *
 * <p><b>Delivery:</b> at-least-once. When the consumer writes only inside the relay's database
 * transaction (for example a projection table), the effect is effectively exactly-once. External
 * side effects (HTTP, Kafka) must be idempotent.
 *
 * <p><b>Memory model:</b> an outbox row can become visible before {@code ChangePipeline} has applied
 * the revision in memory (persist first, then apply). Consumers that read the live model must check
 * readiness and throw {@link OutboxRetryLater} when the revision is not applied yet.
 */
public interface OutboxConsumer {

    /** Where a brand-new consumer row starts. Only used on first registration. */
    enum StartPosition {
        /** Deliver from the first outbox row. */
        EARLIEST,
        /** Skip rows that already exist at registration time. */
        LATEST
    }

    /** Stable name stored in {@code outbox_consumers.consumer}. Must be globally unique. */
    String name();

    /** Default {@link StartPosition#LATEST}: only rows committed after registration. */
    default StartPosition startFrom() {
        return StartPosition.LATEST;
    }

    /**
     * Processes {@code batch} inside the relay's database transaction.
     *
     * @return the last accepted message id; must be &gt;= the first id and &lt;= the last id. Ids at
     *     or below the return value are treated as done. A value below the batch max means partial
     *     ack: the relay immediately fetches the next batch.
     */
    long accept(List<OutboxMessage> batch);
}
