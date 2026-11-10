package wprebuild.app.events;

import java.util.Objects;

/** An entity whose frame changed because it was the subject of a committed axiom (BS-11). */
public record EntityFrameChanged(String type, long revNo, EventEntity entity) implements ProjectEvent {

    public EntityFrameChanged(long revNo, EventEntity entity) {
        this("EntityFrameChanged", revNo, entity);
    }

    public EntityFrameChanged {
        if (!"EntityFrameChanged".equals(type)) {
            throw new IllegalArgumentException("type must be EntityFrameChanged");
        }
        if (revNo < 0) {
            throw new IllegalArgumentException("revision number must be >= 0");
        }
        Objects.requireNonNull(entity, "entity");
    }
}
