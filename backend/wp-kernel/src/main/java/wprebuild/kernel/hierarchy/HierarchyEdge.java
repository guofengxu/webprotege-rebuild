package wprebuild.kernel.hierarchy;

import java.util.Objects;

/** One directed parent-to-child link in a hierarchy (BS-08). */
public record HierarchyEdge<E>(E parent, E child) {

    public HierarchyEdge {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
    }
}
