package wprebuild.app.revision;

import java.time.Instant;
import java.util.Objects;

/** One revision's metadata. The change payload is not loaded. */
public record RevisionSummary(long revNo, String userId, Instant ts, String description, int changeCount) {

    public RevisionSummary {
        if (revNo < 1) {
            throw new IllegalArgumentException("revision number must be greater than 0");
        }
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(ts, "timestamp");
        Objects.requireNonNull(description, "description");
        if (changeCount < 0) {
            throw new IllegalArgumentException("change count must be >= 0");
        }
    }
}
