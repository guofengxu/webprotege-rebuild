package wprebuild.instances.app;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit DB probes for readiness (P6-57): instance store and projection must both accept
 * {@code SELECT 1}. Liveness stays process-only via {@code management.health.group.liveness}.
 */
@Configuration
class InstancesHealthConfig {

    @Bean(name = "instancesDb")
    HealthIndicator instancesDbHealthIndicator(
            @Qualifier(InstancesDataSourceConfig.INSTANCES_DATA_SOURCE) DataSource instancesDataSource) {
        return new DataSourceHealthIndicator(instancesDataSource, "SELECT 1");
    }

    @Bean(name = "projectionDb")
    HealthIndicator projectionDbHealthIndicator(
            @Qualifier(InstancesDataSourceConfig.PROJECTION_DATA_SOURCE) DataSource projectionDataSource) {
        return new DataSourceHealthIndicator(projectionDataSource, "SELECT 1");
    }
}
