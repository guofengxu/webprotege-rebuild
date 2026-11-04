package wprebuild.kernel.crud;

import java.util.Objects;
import wprebuild.kernel.id.UserId;

/** Inclusive numeric id interval assigned to one user (BS-06). */
public record UserIdRange(UserId userId, long start, long end) {

    public UserIdRange {
        Objects.requireNonNull(userId, "user id");
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("user id range must satisfy 0 <= start <= end");
        }
    }
}
