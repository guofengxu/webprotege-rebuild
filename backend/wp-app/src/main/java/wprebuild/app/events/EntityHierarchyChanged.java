package wprebuild.app.events;

import java.util.List;
import java.util.Objects;

/** Parent/child edges added or removed in one hierarchy (BS-11). {@code hierarchy} is the path value. */
public record EntityHierarchyChanged(
        String type, long revNo, String hierarchy, List<HierarchyLink> added, List<HierarchyLink> removed)
        implements ProjectEvent {

    public EntityHierarchyChanged(
            long revNo, String hierarchy, List<HierarchyLink> added, List<HierarchyLink> removed) {
        this("EntityHierarchyChanged", revNo, hierarchy, added, removed);
    }

    public EntityHierarchyChanged {
        if (!"EntityHierarchyChanged".equals(type)) {
            throw new IllegalArgumentException("type must be EntityHierarchyChanged");
        }
        if (revNo < 0) {
            throw new IllegalArgumentException("revision number must be >= 0");
        }
        Objects.requireNonNull(hierarchy, "hierarchy");
        if (hierarchy.isBlank()) {
            throw new IllegalArgumentException("hierarchy is blank");
        }
        added = List.copyOf(Objects.requireNonNull(added, "added"));
        removed = List.copyOf(Objects.requireNonNull(removed, "removed"));
        if (added.isEmpty() && removed.isEmpty()) {
            throw new IllegalArgumentException("hierarchy change has no edges");
        }
    }
}
