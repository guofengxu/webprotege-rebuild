package wprebuild.instances.app;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code wp.projection.datasource.*} — read-only editor projection database. */
@ConfigurationProperties(prefix = "wp.projection.datasource")
public record ProjectionDataSourceProperties(String url, String username, String password) {

    public ProjectionDataSourceProperties {
        requireText(url, "wp.projection.datasource.url");
        requireText(username, "wp.projection.datasource.username");
        Objects.requireNonNull(password, "wp.projection.datasource.password is required");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
