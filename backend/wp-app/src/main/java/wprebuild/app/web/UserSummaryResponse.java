package wprebuild.app.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import wprebuild.app.access.UserSummary;

/** Response item for member-candidate search. Email and roles are intentionally omitted. */
@Schema(name = "UserSummaryResponse")
public record UserSummaryResponse(String userId, String displayName) {

    public UserSummaryResponse {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(displayName, "display name");
    }

    static UserSummaryResponse from(UserSummary summary) {
        return new UserSummaryResponse(summary.userId().value(), summary.displayName());
    }
}
