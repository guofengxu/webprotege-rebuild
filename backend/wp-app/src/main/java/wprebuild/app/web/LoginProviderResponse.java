package wprebuild.app.web;

import java.util.Objects;

/** One entry from {@code GET /api/auth/providers}. */
public record LoginProviderResponse(String id, String displayName, String loginUrl) {

    public LoginProviderResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "display name");
        Objects.requireNonNull(loginUrl, "login url");
    }
}
