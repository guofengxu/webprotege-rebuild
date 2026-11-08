package wprebuild.app.access;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A stored key without its secret. {@code lastUsedAt} is absent until the key authenticates a request. */
public record ApiKeyInfo(UUID keyId, String name, Instant createdAt, Instant lastUsedAt) {

    public ApiKeyInfo {
        Objects.requireNonNull(keyId, "key id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(createdAt, "created at");
    }
}
