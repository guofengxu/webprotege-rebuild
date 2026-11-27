package wprebuild.instances.app;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code wp.instances.*} — single-tenant project id, instance-store datasource, API key settings, and
 * batch write limits (P6-57 / P6-58 / P6-59).
 */
@ConfigurationProperties(prefix = "wp.instances")
public record InstancesProperties(
        UUID projectId,
        DataSourceSettings datasource,
        String apiKeyPepper,
        String bootstrapApiKey,
        Integer maxBatchOps,
        Integer maxRequestBytes,
        Duration maxFutureSkew) {

    public static final int DEFAULT_MAX_BATCH_OPS = 1000;
    public static final int DEFAULT_MAX_REQUEST_BYTES = 1_048_576;
    public static final Duration DEFAULT_MAX_FUTURE_SKEW = Duration.ofMinutes(5);

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
        if (maxBatchOps == null) {
            maxBatchOps = DEFAULT_MAX_BATCH_OPS;
        } else if (maxBatchOps < 1) {
            throw new IllegalArgumentException("wp.instances.max-batch-ops must be >= 1");
        }
        if (maxRequestBytes == null) {
            maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES;
        } else if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("wp.instances.max-request-bytes must be >= 1");
        }
        if (maxFutureSkew == null) {
            maxFutureSkew = DEFAULT_MAX_FUTURE_SKEW;
        } else if (maxFutureSkew.isNegative() || maxFutureSkew.isZero()) {
            throw new IllegalArgumentException("wp.instances.max-future-skew must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record DataSourceSettings(String url, String username, String password) {}
}
