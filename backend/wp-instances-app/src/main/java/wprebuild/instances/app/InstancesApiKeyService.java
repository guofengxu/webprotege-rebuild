package wprebuild.instances.app;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Validates and revokes instance-store API keys. Creation in this phase is bootstrap config or ops SQL only
 * (P6-58) — no management REST.
 */
@Service
public class InstancesApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(InstancesApiKeyService.class);

    private final JdbcInstancesApiKeyRepository keys;
    private final InstancesApiKeyHasher hasher;

    public InstancesApiKeyService(JdbcInstancesApiKeyRepository keys, InstancesProperties properties) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.hasher = new InstancesApiKeyHasher(properties.apiKeyPepper());
    }

    /** Empty when missing, malformed, unknown, or revoked. Never logs the presented key. */
    public Optional<UUID> authenticate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        String hash;
        try {
            hash = hasher.hash(plaintext.strip());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return keys.findActiveIdByHash(hash);
    }

    public void revoke(UUID keyId) {
        Objects.requireNonNull(keyId, "key id");
        int updated = keys.revoke(keyId, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("unknown or already revoked api key: " + keyId);
        }
        log.info("revoked instances api key {}", keyId);
    }

    /** Active key display name for {@code source} when the batch body omits it (P6-59). */
    public Optional<String> activeKeyName(UUID keyId) {
        Objects.requireNonNull(keyId, "key id");
        return keys.findActiveNameById(keyId);
    }

    /**
     * Inserts one active key when the table has none. Used by bootstrap and documented for ops SQL parity.
     * The insert is conditional in SQL so concurrent bootstrap does not create two actives.
     *
     * @return empty when an active key already exists
     */
    Optional<UUID> insertIfNoActiveKey(String name, String plaintext) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(plaintext, "plaintext");
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        try {
            int inserted = keys.insertIfNoActive(id, name, hasher.hash(plaintext), createdAt);
            if (inserted == 0) {
                return Optional.empty();
            }
        } catch (DataIntegrityViolationException ex) {
            // Concurrent bootstrap with the same plaintext hit key_hash UNIQUE.
            log.info("skipped instances api key insert due to concurrent bootstrap");
            return Optional.empty();
        }
        log.info("inserted instances api key {} ({})", id, name);
        return Optional.of(id);
    }

    /** Exposed for ops documentation examples and tests that need the same digest as the service. */
    String hashForOps(String plaintext) {
        return hasher.hash(plaintext);
    }
}
