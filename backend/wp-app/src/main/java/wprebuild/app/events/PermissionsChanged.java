package wprebuild.app.events;

import java.util.Objects;

/**
 * Role assignments for one project changed (BS-11).
 *
 * <p>{@code revNo} is the project head at the time of the change, so the event shares the revision
 * cursor with ontology events. {@code projectId} is the UUID text.
 */
public record PermissionsChanged(String type, long revNo, String projectId) implements ProjectEvent {

    public PermissionsChanged(long revNo, String projectId) {
        this("PermissionsChanged", revNo, projectId);
    }

    public PermissionsChanged {
        if (!"PermissionsChanged".equals(type)) {
            throw new IllegalArgumentException("type must be PermissionsChanged");
        }
        if (revNo < 0) {
            throw new IllegalArgumentException("revision number must be >= 0");
        }
        Objects.requireNonNull(projectId, "project id");
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("project id is blank");
        }
    }
}
