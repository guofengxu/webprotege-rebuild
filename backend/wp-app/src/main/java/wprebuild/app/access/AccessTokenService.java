package wprebuild.app.access;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.access.JdbcAccessTokenRepository.CurrentToken;
import wprebuild.kernel.id.UserId;

/**
 * Issues and checks mobile access tokens. Plaintext exists only in the {@link IssuedTokens} returned by
 * {@link #issue} and {@link #refresh}.
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Service
public class AccessTokenService {

    static final int MAX_DEVICE_NAME_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);

    private final JdbcAccessTokenRepository tokens;

    private final UserService users;

    private final TokenProperties properties;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    public AccessTokenService(
            JdbcAccessTokenRepository tokens, UserService users, TokenProperties properties, Clock clock) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.users = Objects.requireNonNull(users, "users");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public IssuedTokens issue(UserId userId, String deviceName) {
        Objects.requireNonNull(userId, "user id");
        String device = normalizeDevice(deviceName);
        if (users.findById(userId).isEmpty()) {
            throw new IllegalArgumentException("unknown user: " + userId.value());
        }
        Instant now = now();
        String accessToken = newSecret(AccessTokenTokens.ACCESS_PREFIX);
        String refreshToken = newSecret(AccessTokenTokens.REFRESH_PREFIX);
        UUID tokenId = UUID.randomUUID();
        Instant accessExpiresAt = expiry(now, properties.accessTtl());
        Instant refreshExpiresAt = expiry(now, properties.refreshTtl());
        tokens.insert(
                tokenId,
                userId,
                device,
                AccessTokenTokens.sha256(accessToken),
                AccessTokenTokens.sha256(refreshToken),
                accessExpiresAt,
                refreshExpiresAt,
                now);
        log.info("issued access token {} for {}", tokenId, userId.value());
        return new IssuedTokens(tokenId, accessToken, accessExpiresAt, refreshToken, refreshExpiresAt);
    }

    /** Looks the access token up by SHA-256. Expired and revoked tokens are absent. */
    @Transactional
    public Optional<UserId> authenticate(String accessToken) {
        return AccessTokenTokens.accessDigest(accessToken).flatMap(digest -> tokens.authenticate(digest, now()));
    }

    /**
     * Rotates the refresh token. The previous refresh token stops working immediately. Presenting it again revokes
     * the device session.
     *
     * <p>{@link RefreshTokenReusedException} is thrown after the revoke is written, so that write must commit. The
     * other declared failures are thrown before any write.
     */
    @Transactional(
            noRollbackFor = {
                RefreshTokenReusedException.class,
                PasswordResetRequiredException.class,
                InvalidRefreshTokenException.class
            })
    public IssuedTokens refresh(String refreshToken) {
        byte[] digest = AccessTokenTokens.refreshDigest(refreshToken).orElseThrow(InvalidRefreshTokenException::new);
        Instant now = now();
        Optional<UserId> owner = tokens.currentOwner(digest, now);
        if (owner.isPresent()) {
            if (passwordResetRequired(owner.get())) {
                throw new PasswordResetRequiredException();
            }
            Optional<IssuedTokens> rotated = rotate(digest, now);
            if (rotated.isPresent()) {
                return rotated.get();
            }
        }
        Optional<UUID> reused = tokens.findRotated(digest);
        if (reused.isPresent()) {
            tokens.revokeSession(reused.get(), now);
            log.warn("refresh token reused; revoked device session {}", reused.get());
            throw new RefreshTokenReusedException();
        }
        throw new InvalidRefreshTokenException();
    }

    public List<DeviceSession> list(UserId userId) {
        return tokens.listActive(Objects.requireNonNull(userId, "user id"));
    }

    @Transactional
    public void revoke(UserId userId, UUID tokenId) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(tokenId, "token id");
        if (tokens.revoke(userId, tokenId, now()) == 0) {
            throw new UnknownDeviceException();
        }
        log.info("revoked access token {} for {}", tokenId, userId.value());
    }

    private Optional<IssuedTokens> rotate(byte[] previousRefreshDigest, Instant now) {
        String accessToken = newSecret(AccessTokenTokens.ACCESS_PREFIX);
        String refreshToken = newSecret(AccessTokenTokens.REFRESH_PREFIX);
        Instant accessExpiresAt = expiry(now, properties.accessTtl());
        Instant refreshExpiresAt = expiry(now, properties.refreshTtl());
        Optional<CurrentToken> updated = tokens.rotate(
                previousRefreshDigest,
                AccessTokenTokens.sha256(accessToken),
                AccessTokenTokens.sha256(refreshToken),
                accessExpiresAt,
                refreshExpiresAt,
                now);
        if (updated.isEmpty()) {
            return Optional.empty();
        }
        CurrentToken session = updated.get();
        log.info(
                "rotated access token {} for {}",
                session.tokenId(),
                session.userId().value());
        return Optional.of(
                new IssuedTokens(session.tokenId(), accessToken, accessExpiresAt, refreshToken, refreshExpiresAt));
    }

    private boolean passwordResetRequired(UserId userId) {
        return users.findById(userId).map(UserAccount::passwordResetRequired).orElse(true);
    }

    private String newSecret(String prefix) {
        byte[] randomBytes = new byte[AccessTokenTokens.RANDOM_BYTES];
        random.nextBytes(randomBytes);
        try {
            return AccessTokenTokens.encode(prefix, randomBytes);
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant expiry(Instant now, Duration ttl) {
        return now.plus(ttl).truncatedTo(ChronoUnit.MICROS);
    }

    private static String normalizeDevice(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            throw new IllegalArgumentException("device name must not be blank");
        }
        String normalized = deviceName.strip();
        if (normalized.length() > MAX_DEVICE_NAME_LENGTH) {
            throw new IllegalArgumentException("device name must be at most " + MAX_DEVICE_NAME_LENGTH + " characters");
        }
        return normalized;
    }
}
