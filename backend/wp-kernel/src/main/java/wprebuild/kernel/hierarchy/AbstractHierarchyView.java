package wprebuild.kernel.hierarchy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Parent and child index shared by the BS-08 hierarchy views.
 *
 * <p>A structural root ({@code owl:Thing}, {@code owl:topObjectProperty}, {@code owl:topDataProperty})
 * is always present and is never recomputed. An entity with no named parent is linked to that
 * root. Annotation properties have no structural root, so an entity with no named parent is itself
 * a root. {@code pathsToRoot} stops when it repeats a node, so a cycle cannot loop.
 * {@code onApplied} recomputes only entities in the change signature.
 */
public abstract class AbstractHierarchyView<E extends OWLEntity> implements HierarchyView<E> {

    private final Optional<E> structuralRoot;

    private final Map<E, Set<E>> parents = new LinkedHashMap<>();

    private final Map<E, Set<E>> children = new LinkedHashMap<>();

    private HierarchyDelta<E> lastDelta = HierarchyDelta.empty();

    protected AbstractHierarchyView(E structuralRoot) {
        this.structuralRoot = Optional.of(Objects.requireNonNull(structuralRoot, "structuralRoot"));
        installStructuralRoot();
    }

    /** Hierarchy with no universal root (annotation properties). */
    protected AbstractHierarchyView() {
        this.structuralRoot = Optional.empty();
    }

    protected abstract boolean isKind(OWLEntity entity);

    protected abstract E asKind(OWLEntity entity);

    /**
     * Named parents asserted for {@code entity}. Empty means there is no named parent; the view
     * then links {@code entity} to the structural root when this hierarchy has one (BS-08).
     */
    protected abstract Set<E> namedParents(ProjectOntologies ontologies, E entity);

    @Override
    public final void rebuild(ProjectOntologies ontologies) {
        Objects.requireNonNull(ontologies, "ontologies");
        parents.clear();
        children.clear();
        installStructuralRoot();
        EdgeChanges<E> discarded = EdgeChanges.discarding();
        for (E entity : entitiesIn(ontologies)) {
            if (!isStructuralRoot(entity)) {
                replaceParents(entity, resolvedParents(ontologies, entity), discarded);
            }
        }
        lastDelta = HierarchyDelta.empty();
    }

    @Override
    public final void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after) {
        Objects.requireNonNull(effectiveChanges, "effectiveChanges");
        Objects.requireNonNull(after, "ontologies");
        Set<E> present = entitiesIn(after);
        EdgeChanges<E> changes = EdgeChanges.recording();
        for (E entity : affectedEntities(effectiveChanges)) {
            if (isStructuralRoot(entity)) {
                continue;
            }
            if (present.contains(entity)) {
                replaceParents(entity, resolvedParents(after, entity), changes);
            } else {
                detach(entity, changes);
            }
        }
        installStructuralRoot();
        lastDelta = changes.toDelta();
    }

    @Override
    public final Set<E> roots() {
        Set<E> roots = new LinkedHashSet<>();
        for (Map.Entry<E, Set<E>> entry : parents.entrySet()) {
            if (entry.getValue().isEmpty()) {
                roots.add(entry.getKey());
            }
        }
        return Set.copyOf(roots);
    }

    @Override
    public final Set<E> children(E entity) {
        Objects.requireNonNull(entity, "entity");
        return Set.copyOf(children.getOrDefault(entity, Set.of()));
    }

    @Override
    public final Set<E> parents(E entity) {
        Objects.requireNonNull(entity, "entity");
        return Set.copyOf(parents.getOrDefault(entity, Set.of()));
    }

    @Override
    public final boolean hasChildren(E entity) {
        Objects.requireNonNull(entity, "entity");
        Set<E> kids = children.get(entity);
        return kids != null && !kids.isEmpty();
    }

    @Override
    public final List<List<E>> pathsToRoot(E entity) {
        Objects.requireNonNull(entity, "entity");
        if (!parents.containsKey(entity)) {
            return List.of();
        }
        List<List<E>> paths = new ArrayList<>();
        collectPaths(entity, new ArrayDeque<>(), new HashSet<>(), paths);
        return List.copyOf(paths);
    }

    @Override
    public final HierarchyDelta<E> lastDelta() {
        return lastDelta;
    }

    private void installStructuralRoot() {
        structuralRoot.ifPresent(root -> {
            parents.computeIfAbsent(root, ignored -> new LinkedHashSet<>());
            children.computeIfAbsent(root, ignored -> new LinkedHashSet<>());
        });
    }

    private boolean isStructuralRoot(E entity) {
        return structuralRoot.filter(entity::equals).isPresent();
    }

    private Set<E> entitiesIn(ProjectOntologies ontologies) {
        Set<E> present = new LinkedHashSet<>();
        ontologies.signature().filter(this::isKind).map(this::asKind).forEach(present::add);
        return present;
    }

    private Set<E> affectedEntities(List<OntologyChange> changes) {
        Set<E> affected = new LinkedHashSet<>();
        for (OntologyChange change : changes) {
            for (OWLEntity entity : change.signature()) {
                if (isKind(entity)) {
                    affected.add(asKind(entity));
                }
            }
        }
        return affected;
    }

    private Set<E> resolvedParents(ProjectOntologies ontologies, E entity) {
        Set<E> named = Objects.requireNonNull(namedParents(ontologies, entity), "namedParents");
        if (named.isEmpty()) {
            return structuralRoot.map(Set::of).orElseGet(Set::of);
        }
        return named;
    }

    private void replaceParents(E child, Set<E> newParents, EdgeChanges<E> changes) {
        Set<E> stored = new LinkedHashSet<>(newParents);
        Set<E> oldParents = parents.getOrDefault(child, Set.of());
        children.computeIfAbsent(child, ignored -> new LinkedHashSet<>());
        if (oldParents.equals(stored)) {
            parents.put(child, stored);
            return;
        }
        for (E parent : oldParents) {
            if (!stored.contains(parent)) {
                unlink(parent, child);
                changes.recordRemoved(parent, child);
            }
        }
        for (E parent : stored) {
            if (!oldParents.contains(parent)) {
                link(parent, child);
                changes.recordAdded(parent, child);
            }
        }
        parents.put(child, stored);
    }

    private void detach(E child, EdgeChanges<E> changes) {
        Set<E> oldParents = parents.remove(child);
        children.remove(child);
        if (oldParents == null) {
            return;
        }
        for (E parent : oldParents) {
            unlink(parent, child);
            changes.recordRemoved(parent, child);
        }
    }

    private void link(E parent, E child) {
        children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(child);
    }

    private void unlink(E parent, E child) {
        Set<E> kids = children.get(parent);
        if (kids != null) {
            kids.remove(child);
        }
    }

    private void collectPaths(E node, Deque<E> stack, Set<E> onPath, List<List<E>> out) {
        if (!onPath.add(node)) {
            return;
        }
        stack.addLast(node);
        try {
            Set<E> nodeParents = parents.getOrDefault(node, Set.of());
            if (nodeParents.isEmpty()) {
                out.add(rootFirst(stack));
                return;
            }
            for (E parent : List.copyOf(nodeParents)) {
                collectPaths(parent, stack, onPath, out);
            }
        } finally {
            stack.removeLast();
            onPath.remove(node);
        }
    }

    private static <E> List<E> rootFirst(Deque<E> nodeFirst) {
        ArrayList<E> path = new ArrayList<>(nodeFirst.size());
        Iterator<E> fromRoot = nodeFirst.descendingIterator();
        while (fromRoot.hasNext()) {
            path.add(fromRoot.next());
        }
        return List.copyOf(path);
    }

    private static final class EdgeChanges<E> {

        private final boolean record;

        private final Set<HierarchyEdge<E>> added = new LinkedHashSet<>();

        private final Set<HierarchyEdge<E>> removed = new LinkedHashSet<>();

        private EdgeChanges(boolean record) {
            this.record = record;
        }

        private static <E> EdgeChanges<E> recording() {
            return new EdgeChanges<>(true);
        }

        private static <E> EdgeChanges<E> discarding() {
            return new EdgeChanges<>(false);
        }

        private void recordAdded(E parent, E child) {
            if (record) {
                added.add(new HierarchyEdge<>(parent, child));
            }
        }

        private void recordRemoved(E parent, E child) {
            if (record) {
                removed.add(new HierarchyEdge<>(parent, child));
            }
        }

        private HierarchyDelta<E> toDelta() {
            return new HierarchyDelta<>(added, removed);
        }
    }
}
