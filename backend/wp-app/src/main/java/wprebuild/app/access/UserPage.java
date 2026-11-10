package wprebuild.app.access;

import java.util.List;
import java.util.Objects;

/** One page of accounts, ordered by user id. {@code page} is zero-based. */
public record UserPage(List<UserProfile> users, int page, int size, long total) {

    public static final int MAX_SIZE = 200;

    public UserPage {
        users = List.copyOf(Objects.requireNonNull(users, "users"));
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (total < users.size()) {
            throw new IllegalArgumentException("total is smaller than the page");
        }
    }
}
