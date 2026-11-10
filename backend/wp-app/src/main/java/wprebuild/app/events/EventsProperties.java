package wprebuild.app.events;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wp.events.buffer-size} revisions are retained per project. Heartbeats are comment lines. */
@ConfigurationProperties(prefix = "wp.events")
public record EventsProperties(
        @DefaultValue("200") int bufferSize,
        @DefaultValue("25s") Duration heartbeat) {

    /** BS-11 default: the SSE cursor can replay this many recent revisions. */
    public static final int DEFAULT_BUFFER_SIZE = 200;

    public EventsProperties {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("wp.events.buffer-size must be positive");
        }
        Objects.requireNonNull(heartbeat, "heartbeat");
        if (heartbeat.toMillis() < 1) {
            throw new IllegalArgumentException("wp.events.heartbeat must be at least 1ms");
        }
    }
}
