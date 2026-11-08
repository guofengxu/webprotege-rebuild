package wprebuild.app.web;

import java.util.List;
import java.util.Objects;
import wprebuild.app.access.UserAccount;

/** Signed-in user. The password hash is not part of this view. */
public record AuthUserResponse(String userId, String displayName, String email, List<String> appRoles) {

    public AuthUserResponse {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(displayName, "display name");
        appRoles = List.copyOf(Objects.requireNonNull(appRoles, "app roles"));
    }

    public static AuthUserResponse from(UserAccount account) {
        return new AuthUserResponse(
                account.userId().value(), account.displayName(), account.email(), account.appRoles());
    }
}
