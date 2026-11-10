package wprebuild.app.events;

import java.util.Objects;

/** An entity whose display name changed in the latest committed revision (BS-11). */
public record BrowserTextChanged(String type, long revNo, EventEntity entity, String displayName)
        implements ProjectEvent {

    public BrowserTextChanged(long revNo, EventEntity entity, String displayName) {
        this("BrowserTextChanged", revNo, entity, displayName);
    }

    public BrowserTextChanged {
        if (!"BrowserTextChanged".equals(type)) {
            throw new IllegalArgumentException("type must be BrowserTextChanged");
        }
        if (revNo < 0) {
            throw new IllegalArgumentException("revision number must be >= 0");
        }
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(displayName, "display name");
    }
}
