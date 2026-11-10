package wprebuild.app.access;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One device signed in with an access token. Secrets and digests are not part of this view. */
public record DeviceSession(
        UUID tokenId,
        String deviceName,
        Instant createdAt,
        Instant lastUsedAt,
        Instant accessExpiresAt,
        Instant refreshExpiresAt) {

    public DeviceSession {
        Objects.requireNonNull(tokenId, "token id");
        if (deviceName == null || deviceName.isBlank()) {
            throw new IllegalArgumentException("device name must not be blank");
        }
        Objects.requireNonNull(createdAt, "created at");
        Objects.requireNonNull(accessExpiresAt, "access expiry");
        Objects.requireNonNull(refreshExpiresAt, "refresh expiry");
    }
}
