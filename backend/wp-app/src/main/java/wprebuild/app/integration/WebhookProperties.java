package wprebuild.app.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wp.webhooks.*} settings. Delivery timeouts and retry knobs are added by P6-21. */
@ConfigurationProperties(prefix = "wp.webhooks")
public record WebhookProperties(
        @DefaultValue("false") boolean allowPrivateTargets,
        @DefaultValue("10") int maxPerProject) {

    public WebhookProperties {
        if (maxPerProject < 1) {
            throw new IllegalArgumentException("wp.webhooks.max-per-project must be positive");
        }
    }

    /** Convenience for tests that only override the SSRF switch. */
    public static WebhookProperties allowingPrivateTargets(boolean allow) {
        return new WebhookProperties(allow, 10);
    }
}
