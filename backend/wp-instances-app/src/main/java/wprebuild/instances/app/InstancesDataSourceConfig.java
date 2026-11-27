package wprebuild.instances.app;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import wprebuild.instances.InstanceSchema;

@Configuration
@EnableConfigurationProperties({InstancesProperties.class, ProjectionDataSourceProperties.class})
class InstancesDataSourceConfig {

    static final String INSTANCES_DATA_SOURCE = "instancesDataSource";
    static final String PROJECTION_DATA_SOURCE = "projectionDataSource";

    @Bean(name = INSTANCES_DATA_SOURCE)
    @Primary
    DataSource instancesDataSource(InstancesProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("instances");
        dataSource.setJdbcUrl(properties.datasource().url());
        dataSource.setUsername(properties.datasource().username());
        dataSource.setPassword(properties.datasource().password());
        // Keep readiness probes from hanging for ~30s on a dead host.
        dataSource.setConnectionTimeout(5_000);
        return dataSource;
    }

    @Bean(name = PROJECTION_DATA_SOURCE)
    DataSource projectionDataSource(ProjectionDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("projection");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setReadOnly(true);
        dataSource.setConnectionTimeout(5_000);
        return dataSource;
    }

    /**
     * Runs kernel migrations ({@code classpath:db/instances}) then app migrations
     * ({@code classpath:db/instances-app}) against the instance-store database.
     */
    @Bean
    Flyway instancesAppFlyway(@Qualifier(INSTANCES_DATA_SOURCE) DataSource instancesDataSource) {
        InstanceSchema.migrate(instancesDataSource);
        // Separate history table: kernel migrations already use flyway_schema_history.
        // baselineOnMigrate: schema is non-empty after InstanceSchema.migrate.
        Flyway flyway = Flyway.configure()
                .dataSource(instancesDataSource)
                .locations("classpath:db/instances-app")
                .table("flyway_schema_history_app")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .failOnMissingLocations(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
