package wprebuild.app.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code wp.data-dir} for the per-project Lucene directories. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataDirectoryProperties.class)
class SearchConfig {}
