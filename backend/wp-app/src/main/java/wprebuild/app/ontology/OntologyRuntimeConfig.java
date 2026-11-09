package wprebuild.app.ontology;

import com.github.benmanes.caffeine.cache.Ticker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the runtime cache, its clock, and the snapshot executor. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RuntimeProperties.class, ProjectProperties.class, UploadProperties.class})
class OntologyRuntimeConfig {

    @Bean
    Ticker runtimeCacheTicker() {
        return Ticker.systemTicker();
    }

    @Bean(name = "projectSnapshotExecutor", destroyMethod = "shutdown")
    ExecutorService projectSnapshotExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("wp-snapshot-", 0).factory());
    }
}
