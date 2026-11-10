package wprebuild.app.web.admin;

import wprebuild.app.access.UserProfile;
import wprebuild.app.bus.Command;

/** Creates an account with no application roles. Requires {@code CREATE_ACCOUNT}. */
public record CreateUser(String userId, String displayName, String email, String initialPassword)
        implements Command<UserProfile> {

    public CreateUser {
        userId = AccountText.userId(userId);
        displayName = AccountText.displayName(displayName);
        email = AccountText.email(email);
        initialPassword = AccountText.password(initialPassword);
    }

    @Override
    public String toString() {
        return "CreateUser[userId=" + userId + ", displayName=" + displayName + ", email=" + email
                + ", initialPassword=***]";
    }
}
