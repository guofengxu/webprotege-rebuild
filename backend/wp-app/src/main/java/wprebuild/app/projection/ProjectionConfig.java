package wprebuild.app.projection;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code wp.projection.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProjectionProperties.class)
class ProjectionConfig {}
