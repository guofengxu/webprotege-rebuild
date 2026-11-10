package wprebuild.app.access;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wp.tokens.access-ttl} (default 12h) and {@code wp.tokens.refresh-ttl} (default 30d). */
@ConfigurationProperties(prefix = "wp.tokens")
public record TokenProperties(
        @DefaultValue("12h") Duration accessTtl,
        @DefaultValue("30d") Duration refreshTtl) {

    public TokenProperties {
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()) {
            throw new IllegalArgumentException("wp.tokens.access-ttl must be positive");
        }
        if (refreshTtl == null || refreshTtl.isZero() || refreshTtl.isNegative()) {
            throw new IllegalArgumentException("wp.tokens.refresh-ttl must be positive");
        }
    }
}
