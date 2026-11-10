package wprebuild.app.web;

import java.time.Instant;
import java.util.Objects;
import wprebuild.app.access.IssuedTokens;

/** The one response that contains the access and refresh tokens. Later reads return {@code DeviceSession} only. */
public record IssuedTokensResponse(
        String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {

    public IssuedTokensResponse {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("access token must not be blank");
        }
        Objects.requireNonNull(accessExpiresAt, "access expiry");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refresh token must not be blank");
        }
        Objects.requireNonNull(refreshExpiresAt, "refresh expiry");
    }

    public static IssuedTokensResponse from(IssuedTokens issued) {
        return new IssuedTokensResponse(
                issued.accessToken(), issued.accessExpiresAt(), issued.refreshToken(), issued.refreshExpiresAt());
    }

    @Override
    public String toString() {
        return "IssuedTokensResponse[accessToken=***, accessExpiresAt=" + accessExpiresAt
                + ", refreshToken=***, refreshExpiresAt=" + refreshExpiresAt + "]";
    }
}
