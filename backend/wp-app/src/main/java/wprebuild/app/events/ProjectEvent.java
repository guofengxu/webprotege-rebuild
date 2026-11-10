package wprebuild.app.events;

/**
 * One high-level project event derived from a committed revision or a role change (BS-11).
 *
 * <p>{@link #revNo()} is the SSE id and the long-poll cursor. Several events may share one revision.
 */
public sealed interface ProjectEvent
        permits ProjectChanged, EntityHierarchyChanged, EntityFrameChanged, BrowserTextChanged, PermissionsChanged {

    String type();

    long revNo();
}
