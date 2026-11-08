package wprebuild.app.access;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.kernel.id.UserId;

/**
 * Issues and checks API keys. The plaintext exists only in the {@link IssuedKey} returned by {@link #issue}.
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Service
public class ApiKeyService {

    static final int MAX_NAME_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final JdbcApiKeyRepository keys;

    private final UserService users;

    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcApiKeyRepository keys, UserService users) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.users = Objects.requireNonNull(users, "users");
    }

    @Transactional
    public IssuedKey issue(UserId userId, String name) {
        Objects.requireNonNull(userId, "user id");
        String normalized = normalizeName(name);
        if (users.findById(userId).isEmpty()) {
            throw new IllegalArgumentException("unknown user: " + userId.value());
        }
        byte[] randomBytes = new byte[ApiKeyTokens.RANDOM_BYTES];
        random.nextBytes(randomBytes);
        String plaintext = ApiKeyTokens.encode(randomBytes);
        Arrays.fill(randomBytes, (byte) 0);
        UUID keyId = UUID.randomUUID();
        var createdAt = keys.insert(keyId, userId, normalized, ApiKeyTokens.sha256(plaintext));
        log.info("issued api key {} for {}", keyId, userId.value());
        return new IssuedKey(keyId, normalized, plaintext, createdAt);
    }

    /** Looks the key up by SHA-256 and, on a hit, sets {@code last_used_at}. */
    @Transactional
    public Optional<UserId> authenticate(String plaintext) {
        return ApiKeyTokens.digestIfWellFormed(plaintext).flatMap(keys::authenticate);
    }

    public List<ApiKeyInfo> list(UserId userId) {
        return keys.list(Objects.requireNonNull(userId, "user id"));
    }

    @Transactional
    public void revoke(UserId userId, UUID keyId) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(keyId, "key id");
        if (keys.delete(userId, keyId) == 0) {
            throw new UnknownApiKeyException();
        }
        log.info("revoked api key {} for {}", keyId, userId.value());
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String normalized = name.strip();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }
}
