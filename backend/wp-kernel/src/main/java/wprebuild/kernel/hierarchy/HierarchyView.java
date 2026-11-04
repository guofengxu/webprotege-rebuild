package wprebuild.kernel.hierarchy;

import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.runtime.DerivedView;

/**
 * Parent and child links for one kind of entity (BS-08).
 *
 * <p>Each path from {@link #pathsToRoot(OWLEntity)} starts at a root and ends at the entity. A
 * cycle does not extend a path. {@link #lastDelta()} is the edge change from the latest
 * {@code onApplied}; {@code rebuild} clears it.
 */
public interface HierarchyView<E extends OWLEntity> extends DerivedView {

    Set<E> roots();

    Set<E> children(E entity);

    Set<E> parents(E entity);

    boolean hasChildren(E entity);

    List<List<E>> pathsToRoot(E entity);

    HierarchyDelta<E> lastDelta();
}
