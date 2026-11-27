package wprebuild.instances.app;

import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code wp.instances.*} — single-tenant project id and instance-store datasource. */
@ConfigurationProperties(prefix = "wp.instances")
public record InstancesProperties(UUID projectId, DataSourceSettings datasource) {

    public InstancesProperties {
        if (projectId == null) {
            throw new IllegalArgumentException("wp.instances.project-id is required");
        }
        if (datasource == null) {
            throw new IllegalArgumentException("wp.instances.datasource is required");
        }
        requireText(datasource.url(), "wp.instances.datasource.url");
        requireText(datasource.username(), "wp.instances.datasource.username");
        Objects.requireNonNull(datasource.password(), "wp.instances.datasource.password is required");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record DataSourceSettings(String url, String username, String password) {}
}
