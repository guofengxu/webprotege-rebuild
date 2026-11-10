package wprebuild.app.web.admin;

import wprebuild.app.access.UserPage;
import wprebuild.app.bus.Command;

/**
 * Lists accounts. BS-10 has no separate view-users action; only {@code SYSTEM_ADMIN} holds
 * {@code CREATE_ACCOUNT}, so the directory stays with account administration.
 */
public record ListUsers(int page, int size) implements Command<UserPage> {

    public ListUsers {
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1 || size > UserPage.MAX_SIZE) {
            throw new IllegalArgumentException("size must be from 1 to " + UserPage.MAX_SIZE);
        }
    }
}
