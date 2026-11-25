package wprebuild.app.web.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wp.security.deleted-check-ttl} (default 30s) for the per-request deleted-account cache. */
@ConfigurationProperties(prefix = "wp.security")
public record SecurityProperties(@DefaultValue("30s") Duration deletedCheckTtl) {

    public SecurityProperties {
        if (deletedCheckTtl == null || deletedCheckTtl.isNegative()) {
            throw new IllegalArgumentException("wp.security.deleted-check-ttl must not be negative");
        }
    }
}
