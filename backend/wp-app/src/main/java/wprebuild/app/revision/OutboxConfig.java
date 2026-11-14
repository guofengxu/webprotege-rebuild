package wprebuild.app.revision;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code wp.outbox.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfig {}
