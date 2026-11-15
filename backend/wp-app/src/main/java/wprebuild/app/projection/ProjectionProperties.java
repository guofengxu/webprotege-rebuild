package wprebuild.app.projection;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wp.projection.*} settings for the outbox-driven projection consumer. */
@ConfigurationProperties(prefix = "wp.projection")
public record ProjectionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5000") int fullRebuildThreshold) {

    public ProjectionProperties {
        if (fullRebuildThreshold < 1) {
            throw new IllegalArgumentException("wp.projection.full-rebuild-threshold must be positive");
        }
    }
}
