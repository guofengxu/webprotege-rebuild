package wprebuild.app.ontology;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long a project stays loaded, how many may stay loaded, and how often to snapshot. */
@ConfigurationProperties(prefix = "wp.runtime")
public record RuntimeProperties(
        @DefaultValue("30m") Duration idleTimeout,
        @DefaultValue("20") int maxLoaded,
        @DefaultValue("500") int snapshotEvery) {

    public RuntimeProperties {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("wp.runtime.idle-timeout must be positive");
        }
        if (maxLoaded < 1) {
            throw new IllegalArgumentException("wp.runtime.max-loaded must be positive");
        }
        if (snapshotEvery < 1) {
            throw new IllegalArgumentException("wp.runtime.snapshot-every must be positive");
        }
    }
}
