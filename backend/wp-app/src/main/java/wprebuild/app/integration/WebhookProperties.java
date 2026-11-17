package wprebuild.app.integration;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wp.webhooks.*} settings for management (P6-20) and delivery (P6-21). */
@ConfigurationProperties(prefix = "wp.webhooks")
public record WebhookProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean allowPrivateTargets,
        @DefaultValue("10") int maxPerProject,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("8") int maxConcurrency,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("1s") Duration retryBase,
        @DefaultValue("1h") Duration retryMax,
        @DefaultValue("30d") Duration retention) {

    public WebhookProperties {
        Objects.requireNonNull(pollInterval, "poll interval");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(connectTimeout, "connect timeout");
        Objects.requireNonNull(requestTimeout, "request timeout");
        Objects.requireNonNull(retryBase, "retry base");
        Objects.requireNonNull(retryMax, "retry max");
        Objects.requireNonNull(retention, "retention");
        if (maxPerProject < 1) {
            throw new IllegalArgumentException("wp.webhooks.max-per-project must be positive");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("wp.webhooks.max-concurrency must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("wp.webhooks.max-attempts must be positive");
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("wp.webhooks.poll-interval must be positive");
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("wp.webhooks.lease must be positive");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("wp.webhooks.connect-timeout must be positive");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("wp.webhooks.request-timeout must be positive");
        }
        if (retryBase.isNegative() || retryBase.isZero()) {
            throw new IllegalArgumentException("wp.webhooks.retry-base must be positive");
        }
        if (retryMax.isNegative() || retryMax.isZero()) {
            throw new IllegalArgumentException("wp.webhooks.retry-max must be positive");
        }
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("wp.webhooks.retention must be positive");
        }
        Duration timeouts = connectTimeout.plus(requestTimeout);
        if (lease.compareTo(timeouts) <= 0) {
            throw new IllegalArgumentException(
                    "wp.webhooks.lease must be greater than connect-timeout + request-timeout");
        }
    }

    /** Convenience for tests that only override the SSRF switch. */
    public static WebhookProperties allowingPrivateTargets(boolean allow) {
        return new WebhookProperties(
                true,
                allow,
                10,
                Duration.ofSeconds(1),
                8,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                8,
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofDays(30));
    }
}
