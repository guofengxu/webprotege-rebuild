package wprebuild.instances.app;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import wprebuild.instances.InstanceStore;
import wprebuild.instances.rules.CachedRuleSource;

/** Wires {@link InstanceStore} and {@link CachedRuleSource} for the batch write API (P6-59). */
@Configuration
class InstancesWriteConfig {

    @Bean
    InstanceStore instanceStore(@Qualifier(InstancesDataSourceConfig.INSTANCES_DATA_SOURCE) DataSource dataSource) {
        return new InstanceStore(dataSource);
    }

    @Bean
    CachedRuleSource cachedRuleSource(
            @Qualifier(InstancesDataSourceConfig.PROJECTION_DATA_SOURCE) DataSource projectionDataSource) {
        return new CachedRuleSource(projectionDataSource);
    }
}
