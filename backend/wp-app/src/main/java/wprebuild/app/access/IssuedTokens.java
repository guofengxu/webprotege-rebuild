package wprebuild.app.access;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A newly issued access token and refresh token. The plaintext exists only in this value. */
public record IssuedTokens(
        UUID tokenId, String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {

    public IssuedTokens {
        Objects.requireNonNull(tokenId, "token id");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("access token must not be blank");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refresh token must not be blank");
        }
        if (accessToken.equals(refreshToken)) {
            throw new IllegalArgumentException("access and refresh tokens must differ");
        }
        Objects.requireNonNull(accessExpiresAt, "access expiry");
        Objects.requireNonNull(refreshExpiresAt, "refresh expiry");
    }

    @Override
    public String toString() {
        return "IssuedTokens[tokenId=" + tokenId + ", accessToken=***, accessExpiresAt=" + accessExpiresAt
                + ", refreshToken=***, refreshExpiresAt=" + refreshExpiresAt + "]";
    }
}
