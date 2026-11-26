package wprebuild.app.integration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.revision.OutboxConsumer;
import wprebuild.app.revision.OutboxMessage;

/**
 * Publishes each outbox row to Kafka (P6-35). Registered only when {@code wp.kafka.enabled=true}.
 *
 * <p>Runs inside the outbox relay's database transaction: {@code accept} blocks until the producer
 * flush completes or {@code wp.kafka.send-timeout} elapses. Keep the timeout short so a down broker
 * does not hold the relay lock (and thus other consumers) for long. Failures roll back the batch
 * cursor; the next attempt resends the whole batch (at-least-once). Downstream must dedupe by
 * {@code outboxId} / header {@code wp-outbox-id}.
 */
@Component
@ConditionalOnProperty(prefix = "wp.kafka", name = "enabled", havingValue = "true")
public class KafkaOutboxConsumer implements OutboxConsumer {

    static final String CONSUMER_NAME = "kafka";

    static final String HEADER_OUTBOX_ID = "wp-outbox-id";

    static final String HEADER_EVENT_TYPE = "wp-event-type";

    private final KafkaTemplate<String, String> kafka;

    private final KafkaPublisherProperties properties;

    private final JsonMapper json;

    public KafkaOutboxConsumer(
            KafkaTemplate<String, String> kafka, KafkaPublisherProperties properties, JsonMapper json) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public StartPosition startFrom() {
        return properties.consumerStartPosition();
    }

    @Override
    public long accept(List<OutboxMessage> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("outbox batch must not be empty");
        }
        String topic = properties.topic();
        // One wall-clock budget for the whole batch (card: accept blocks about send-timeout).
        long deadlineNanos = System.nanoTime() + properties.sendTimeout().toNanos();
        List<CompletableFuture<SendResult<String, String>>> pending = new ArrayList<>(batch.size());
        for (OutboxMessage message : batch) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException(
                        "kafka send timed out after " + properties.sendTimeout() + " for outbox id=" + message.id());
            }
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, null, message.projectId().toString(), valueJson(message));
            record.headers().add(HEADER_OUTBOX_ID, Long.toString(message.id()).getBytes(StandardCharsets.UTF_8));
            record.headers().add(HEADER_EVENT_TYPE, message.type().getBytes(StandardCharsets.UTF_8));
            pending.add(kafka.send(record));
        }
        kafka.flush();
        for (int i = 0; i < pending.size(); i++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IllegalStateException("kafka send timed out after " + properties.sendTimeout()
                        + " for outbox id=" + batch.get(i).id());
            }
            try {
                pending.get(i).get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException ex) {
                throw new IllegalStateException(
                        "kafka send timed out after " + properties.sendTimeout() + " for outbox id="
                                + batch.get(i).id(),
                        ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                throw new IllegalStateException(
                        "kafka send failed for outbox id=" + batch.get(i).id(), cause);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "kafka send interrupted for outbox id=" + batch.get(i).id(), ex);
            }
        }
        return batch.getLast().id();
    }

    private String valueJson(OutboxMessage message) {
        try {
            JsonNode payload = json.readTree(message.payloadJson());
            ObjectNode body = json.createObjectNode();
            body.put("outboxId", message.id());
            body.put("type", message.type());
            body.put("projectId", message.projectId().toString());
            body.put("revNo", message.revNo());
            body.set("payload", payload);
            body.put("createdAt", message.createdAt().toString());
            return json.writeValueAsString(body);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("invalid outbox payload for kafka id=" + message.id(), ex);
        }
    }
}
