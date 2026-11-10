package wprebuild.app.events;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code wp.events.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventsProperties.class)
class EventsConfig {}
