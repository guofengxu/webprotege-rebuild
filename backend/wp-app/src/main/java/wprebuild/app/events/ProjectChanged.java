package wprebuild.app.events;

import java.time.Instant;
import java.util.Objects;

/** Every committed revision, including a silent one (BS-11). */
public record ProjectChanged(
        String type, long revNo, String user, Instant timestamp, String description, int changeCount)
        implements ProjectEvent {

    public ProjectChanged(long revNo, String user, Instant timestamp, String description, int changeCount) {
        this("ProjectChanged", revNo, user, timestamp, description, changeCount);
    }

    public ProjectChanged {
        if (!"ProjectChanged".equals(type)) {
            throw new IllegalArgumentException("type must be ProjectChanged");
        }
        if (revNo < 0) {
            throw new IllegalArgumentException("revision number must be >= 0");
        }
        Objects.requireNonNull(user, "user");
        if (user.isBlank()) {
            throw new IllegalArgumentException("user is blank");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(description, "description");
        if (changeCount < 0) {
            throw new IllegalArgumentException("change count must be >= 0");
        }
    }
}
