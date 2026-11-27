package wprebuild.instances.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Independent instance-store Spring Boot service (ADR-013 / P6-57).
 *
 * <p>No {@code spring-boot-starter-flyway}: startup runs {@code InstanceSchema.migrate}
 * ({@code classpath:db/instances}) then app Flyway ({@code classpath:db/instances-app})
 * in {@link InstancesDataSourceConfig}.
 */
@SpringBootApplication
public class InstancesApplication {

    public static void main(String[] args) {
        SpringApplication.run(InstancesApplication.class, args);
    }
}
