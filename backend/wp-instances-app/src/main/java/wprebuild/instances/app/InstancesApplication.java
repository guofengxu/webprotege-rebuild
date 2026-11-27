package wprebuild.instances.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Independent instance-store Spring Boot service (ADR-013 / P6-57).
 *
 * <p>No {@code spring-boot-starter-flyway}: startup runs {@code InstanceSchema.migrate}
 * ({@code classpath:db/instances}) then app Flyway ({@code classpath:db/instances-app})
 * in {@link InstancesDataSourceConfig}.
 *
 * <p>Excludes the default in-memory user: this service authenticates only via {@code X-API-Key}.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class InstancesApplication {

    public static void main(String[] args) {
        SpringApplication.run(InstancesApplication.class, args);
    }
}
