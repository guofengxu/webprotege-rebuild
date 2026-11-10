package wprebuild.app.events;

import java.util.Objects;

/** One parent-to-child edge in an {@link EntityHierarchyChanged} payload. Both values are IRIs. */
public record HierarchyLink(String parent, String child) {

    public HierarchyLink {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
        if (parent.isBlank() || child.isBlank()) {
            throw new IllegalArgumentException("hierarchy endpoint is blank");
        }
    }
}
