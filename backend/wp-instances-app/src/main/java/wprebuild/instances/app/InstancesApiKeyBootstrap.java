package wprebuild.instances.app;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Inserts one API key from {@code wp.instances.bootstrap-api-key} when no active key exists (P6-58).
 *
 * <p>Does not log the plaintext. Clear the property after the first successful start in production.
 */
@Component
class InstancesApiKeyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstancesApiKeyBootstrap.class);

    private static final String BOOTSTRAP_NAME = "bootstrap";

    private final InstancesProperties properties;
    private final InstancesApiKeyService apiKeys;

    InstancesApiKeyBootstrap(InstancesProperties properties, InstancesApiKeyService apiKeys) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.apiKeys = Objects.requireNonNull(apiKeys, "api keys");
    }

    @Override
    public void run(ApplicationArguments args) {
        String bootstrap = properties.bootstrapApiKey();
        if (bootstrap == null || bootstrap.isBlank()) {
            log.debug("wp.instances.bootstrap-api-key unset; skipping api key bootstrap");
            return;
        }
        apiKeys.insertIfNoActiveKey(BOOTSTRAP_NAME, bootstrap.strip())
                .ifPresentOrElse(
                        id -> log.info("bootstrapped instances api key {}", id),
                        () -> log.info("skipped api key bootstrap because an active key already exists"));
    }
}
