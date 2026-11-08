package wprebuild.app.web;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import wprebuild.app.access.IssuedKey;

/** The one response that contains {@code apiKey}. Later reads return {@code ApiKeyInfo} only. */
public record IssuedApiKeyResponse(UUID keyId, String name, String apiKey, Instant createdAt) {

    public IssuedApiKeyResponse {
        Objects.requireNonNull(keyId, "key id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api key must not be blank");
        }
        Objects.requireNonNull(createdAt, "created at");
    }

    public static IssuedApiKeyResponse from(IssuedKey issued) {
        return new IssuedApiKeyResponse(issued.keyId(), issued.name(), issued.plaintext(), issued.createdAt());
    }

    @Override
    public String toString() {
        return "IssuedApiKeyResponse[keyId=" + keyId + ", name=" + name + ", apiKey=***, createdAt=" + createdAt + "]";
    }
}
