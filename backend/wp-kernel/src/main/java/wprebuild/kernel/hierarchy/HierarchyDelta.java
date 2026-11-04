package wprebuild.kernel.hierarchy;

import java.util.Objects;
import java.util.Set;

/**
 * Edges added and removed by one hierarchy update (BS-08).
 *
 * <p>The two sets are disjoint. An empty delta is also what {@code rebuild} and a no-op update
 * report.
 */
public record HierarchyDelta<E>(Set<HierarchyEdge<E>> added, Set<HierarchyEdge<E>> removed) {

    public HierarchyDelta {
        Objects.requireNonNull(added, "added");
        Objects.requireNonNull(removed, "removed");
        added = Set.copyOf(added);
        removed = Set.copyOf(removed);
        for (HierarchyEdge<E> edge : added) {
            if (removed.contains(edge)) {
                throw new IllegalArgumentException("edge is both added and removed: " + edge);
            }
        }
    }

    public static <E> HierarchyDelta<E> empty() {
        return new HierarchyDelta<>(Set.of(), Set.of());
    }
}
