package wprebuild.instances.app;

import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code wp.instances.*} — single-tenant project id, instance-store datasource, and API key settings
 * (P6-57 / P6-58).
 */
@ConfigurationProperties(prefix = "wp.instances")
public record InstancesProperties(
        UUID projectId, DataSourceSettings datasource, String apiKeyPepper, String bootstrapApiKey) {

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
        requireText(apiKeyPepper, "wp.instances.api-key-pepper");
        // bootstrapApiKey is optional; blank/null means skip bootstrap insert.
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record DataSourceSettings(String url, String username, String password) {}
}
