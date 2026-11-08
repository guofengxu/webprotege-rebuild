package wprebuild.app.access;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A newly issued key. {@code plaintext} is returned only from {@link ApiKeyService#issue}. */
public record IssuedKey(UUID keyId, String name, String plaintext, Instant createdAt) {

    public IssuedKey {
        Objects.requireNonNull(keyId, "key id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("plaintext must not be blank");
        }
        Objects.requireNonNull(createdAt, "created at");
    }

    @Override
    public String toString() {
        return "IssuedKey[keyId=" + keyId + ", name=" + name + ", plaintext=***, createdAt=" + createdAt + "]";
    }
}
