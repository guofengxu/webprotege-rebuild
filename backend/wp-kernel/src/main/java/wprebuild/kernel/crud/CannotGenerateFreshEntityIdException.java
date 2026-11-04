package wprebuild.kernel.crud;

import java.util.Objects;
import wprebuild.kernel.id.UserId;

/** The user's OBO id range has no unused value left (BS-06). */
public final class CannotGenerateFreshEntityIdException extends RuntimeException {

    private final UserId userId;

    private final UserIdRange range;

    public CannotGenerateFreshEntityIdException(UserId userId, UserIdRange range) {
        super(message(userId, range));
        this.userId = Objects.requireNonNull(userId, "user id");
        this.range = Objects.requireNonNull(range, "range");
    }

    public UserId userId() {
        return userId;
    }

    public UserIdRange range() {
        return range;
    }

    private static String message(UserId userId, UserIdRange range) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(range, "range");
        return "Cannot generate fresh entity id for user "
                + userId.value()
                + ". Allocated id range ("
                + range.start()
                + " - "
                + range.end()
                + ") has been exhausted.";
    }
}
