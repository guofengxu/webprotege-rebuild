package wprebuild.app.integration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Producer settings for {@link KafkaOutboxConsumer}: idempotent, {@code acks=all}, string
 * serializers, and block/delivery timeouts capped at {@code wp.kafka.send-timeout}.
 *
 * <p>Not a transactional producer: the outbox DB transaction and Kafka cannot share one
 * transaction, so delivery is at-least-once and downstream must dedupe by {@code outboxId}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wp.kafka", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaPublisherProperties.class)
class KafkaPublisherConfig {

    @Bean
    DefaultKafkaProducerFactoryCustomizer kafkaPublisherProducerCustomizer(KafkaPublisherProperties properties) {
        Objects.requireNonNull(properties, "properties");
        long timeoutMs = properties.sendTimeout().toMillis();
        if (timeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("wp.kafka.send-timeout exceeds Integer.MAX_VALUE millis");
        }
        int timeout = (int) timeoutMs;
        // request.timeout.ms must stay below delivery.timeout.ms (Kafka producer invariant).
        int requestTimeout = Math.max(1, timeout / 2);
        return factory -> {
            Map<String, Object> updates = new HashMap<>();
            updates.put(ProducerConfig.ACKS_CONFIG, "all");
            updates.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            updates.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            updates.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            updates.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeoutMs);
            updates.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout);
            updates.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeout);
            factory.updateConfigs(updates);
        };
    }
}
