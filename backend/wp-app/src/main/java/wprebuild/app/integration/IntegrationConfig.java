package wprebuild.app.integration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code wp.webhooks.*} and {@code wp.kafka.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WebhookProperties.class, KafkaPublisherProperties.class})
class IntegrationConfig {}
