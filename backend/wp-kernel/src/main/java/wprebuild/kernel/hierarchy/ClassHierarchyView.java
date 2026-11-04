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
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Asserted class hierarchy (BS-08).
 *
 * <p>{@code owl:Thing} is always the only root, even when a subclass axiom names it as the
 * subclass. A class with no named parent hangs under {@code owl:Thing}. {@code onApplied}
 * recomputes only classes in the change signature.
 */
public final class ClassHierarchyView implements HierarchyView<OWLClass> {

    private final OWLClass thing = OWLManager.getOWLDataFactory().getOWLThing();

    private final Map<OWLClass, Set<OWLClass>> parents = new LinkedHashMap<>();

    private final Map<OWLClass, Set<OWLClass>> children = new LinkedHashMap<>();

    private HierarchyDelta<OWLClass> lastDelta = HierarchyDelta.empty();

    public ClassHierarchyView() {
        installRoot();
    }

    @Override
    public void rebuild(ProjectOntologies ontologies) {
        Objects.requireNonNull(ontologies, "ontologies");
        parents.clear();
        children.clear();
        installRoot();
        EdgeChanges discarded = EdgeChanges.discarding();
        for (OWLClass cls : classesIn(ontologies)) {
            if (!cls.equals(thing)) {
                replaceParents(cls, resolvedParents(ontologies, cls), discarded);
            }
        }
        lastDelta = HierarchyDelta.empty();
    }

    @Override
    public void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after) {
        Objects.requireNonNull(effectiveChanges, "effectiveChanges");
        Objects.requireNonNull(after, "ontologies");
        Set<OWLClass> affected = affectedClasses(effectiveChanges);
        Set<OWLClass> present = classesIn(after);
        EdgeChanges changes = EdgeChanges.recording();
        for (OWLClass cls : affected) {
            if (cls.equals(thing)) {
                continue;
            }
            if (present.contains(cls)) {
                replaceParents(cls, resolvedParents(after, cls), changes);
            } else {
                detach(cls, changes);
            }
        }
        installRoot();
        lastDelta = changes.toDelta();
    }

    @Override
    public Set<OWLClass> roots() {
        return Set.of(thing);
    }

    @Override
    public Set<OWLClass> children(OWLClass entity) {
        Objects.requireNonNull(entity, "entity");
        return Set.copyOf(children.getOrDefault(entity, Set.of()));
    }

    @Override
    public Set<OWLClass> parents(OWLClass entity) {
        Objects.requireNonNull(entity, "entity");
        return Set.copyOf(parents.getOrDefault(entity, Set.of()));
    }

    @Override
    public boolean hasChildren(OWLClass entity) {
        Objects.requireNonNull(entity, "entity");
        Set<OWLClass> kids = children.get(entity);
        return kids != null && !kids.isEmpty();
    }

    @Override
    public List<List<OWLClass>> pathsToRoot(OWLClass entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.equals(thing)) {
            return List.of(List.of(thing));
        }
        if (!parents.containsKey(entity)) {
            return List.of();
        }
        List<List<OWLClass>> paths = new ArrayList<>();
        collectPaths(entity, new ArrayDeque<>(), new HashSet<>(), paths);
        return List.copyOf(paths);
    }

    @Override
    public HierarchyDelta<OWLClass> lastDelta() {
        return lastDelta;
    }

    private void installRoot() {
        parents.computeIfAbsent(thing, ignored -> new LinkedHashSet<>());
        children.computeIfAbsent(thing, ignored -> new LinkedHashSet<>());
    }

    private static Set<OWLClass> classesIn(ProjectOntologies ontologies) {
        Set<OWLClass> present = new LinkedHashSet<>();
        ontologies
                .signature()
                .filter(OWLEntity::isOWLClass)
                .map(OWLEntity::asOWLClass)
                .forEach(present::add);
        return present;
    }

    private static Set<OWLClass> affectedClasses(List<OntologyChange> changes) {
        Set<OWLClass> affected = new LinkedHashSet<>();
        for (OntologyChange change : changes) {
            for (OWLEntity entity : change.signature()) {
                if (entity.isOWLClass()) {
                    affected.add(entity.asOWLClass());
                }
            }
        }
        return affected;
    }

    /**
     * Named parents from {@code SubClassOf(C P)} and from named operands of an intersection that
     * is equivalent to {@code C}. An empty result means {@code owl:Thing} (BS-08).
     */
    private Set<OWLClass> resolvedParents(ProjectOntologies ontologies, OWLClass cls) {
        Set<OWLClass> named = namedParents(ontologies, cls);
        if (named.isEmpty()) {
            return Set.of(thing);
        }
        return named;
    }

    private Set<OWLClass> namedParents(ProjectOntologies ontologies, OWLClass cls) {
        Set<OWLClass> named = new LinkedHashSet<>();
        for (OWLAxiom axiom : ontologies.referencingAxioms(cls).toList()) {
            collectNamedParents(axiom, cls, named);
        }
        return named;
    }

    private static void collectNamedParents(OWLAxiom axiom, OWLClass cls, Set<OWLClass> named) {
        switch (axiom) {
            case OWLSubClassOfAxiom sub
            when sub.getSubClass().equals(cls) -> {
                if (sub.getSuperClass() instanceof OWLClass parent) {
                    named.add(parent);
                }
            }
            case OWLEquivalentClassesAxiom equivalent -> addIntersectionParents(equivalent, cls, named);
            default -> {
                // Other axioms do not assert a named class parent (BS-08).
            }
        }
    }

    private static void addIntersectionParents(OWLEquivalentClassesAxiom axiom, OWLClass cls, Set<OWLClass> named) {
        boolean subject = false;
        Set<OWLClass> conjuncts = new LinkedHashSet<>();
        for (OWLClassExpression expression : axiom.getClassExpressions()) {
            if (expression.equals(cls)) {
                subject = true;
            } else if (expression instanceof OWLObjectIntersectionOf intersection) {
                addNamedOperands(intersection, conjuncts);
            }
        }
        if (subject) {
            named.addAll(conjuncts);
        }
    }

    private static void addNamedOperands(OWLObjectIntersectionOf intersection, Set<OWLClass> named) {
        for (OWLClassExpression operand : intersection.getOperands()) {
            if (operand instanceof OWLClass parent) {
                named.add(parent);
            }
        }
    }

    private void replaceParents(OWLClass child, Set<OWLClass> newParents, EdgeChanges changes) {
        Set<OWLClass> stored = new LinkedHashSet<>(newParents);
        Set<OWLClass> oldParents = parents.getOrDefault(child, Set.of());
        children.computeIfAbsent(child, ignored -> new LinkedHashSet<>());
        if (oldParents.equals(stored)) {
            parents.put(child, stored);
            return;
        }
        for (OWLClass parent : oldParents) {
            if (!stored.contains(parent)) {
                unlink(parent, child);
                changes.recordRemoved(parent, child);
            }
        }
        for (OWLClass parent : stored) {
            if (!oldParents.contains(parent)) {
                link(parent, child);
                changes.recordAdded(parent, child);
            }
        }
        parents.put(child, stored);
    }

    private void detach(OWLClass child, EdgeChanges changes) {
        Set<OWLClass> oldParents = parents.remove(child);
        children.remove(child);
        if (oldParents == null) {
            return;
        }
        for (OWLClass parent : oldParents) {
            unlink(parent, child);
            changes.recordRemoved(parent, child);
        }
    }

    private void link(OWLClass parent, OWLClass child) {
        children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(child);
    }

    private void unlink(OWLClass parent, OWLClass child) {
        Set<OWLClass> kids = children.get(parent);
        if (kids != null) {
            kids.remove(child);
        }
    }

    private void collectPaths(OWLClass node, Deque<OWLClass> stack, Set<OWLClass> onPath, List<List<OWLClass>> out) {
        if (!onPath.add(node)) {
            return;
        }
        stack.addLast(node);
        try {
            if (node.equals(thing)) {
                out.add(rootFirst(stack));
                return;
            }
            for (OWLClass parent : List.copyOf(parents.getOrDefault(node, Set.of()))) {
                collectPaths(parent, stack, onPath, out);
            }
        } finally {
            stack.removeLast();
            onPath.remove(node);
        }
    }

    private static List<OWLClass> rootFirst(Deque<OWLClass> nodeFirst) {
        ArrayList<OWLClass> path = new ArrayList<>(nodeFirst.size());
        Iterator<OWLClass> fromRoot = nodeFirst.descendingIterator();
        while (fromRoot.hasNext()) {
            path.add(fromRoot.next());
        }
        return List.copyOf(path);
    }

    private static final class EdgeChanges {

        private final boolean record;

        private final Set<HierarchyEdge<OWLClass>> added = new LinkedHashSet<>();

        private final Set<HierarchyEdge<OWLClass>> removed = new LinkedHashSet<>();

        private EdgeChanges(boolean record) {
            this.record = record;
        }

        private static EdgeChanges recording() {
            return new EdgeChanges(true);
        }

        private static EdgeChanges discarding() {
            return new EdgeChanges(false);
        }

        private void recordAdded(OWLClass parent, OWLClass child) {
            if (record) {
                added.add(new HierarchyEdge<>(parent, child));
            }
        }

        private void recordRemoved(OWLClass parent, OWLClass child) {
            if (record) {
                removed.add(new HierarchyEdge<>(parent, child));
            }
        }

        private HierarchyDelta<OWLClass> toDelta() {
            return new HierarchyDelta<>(added, removed);
        }
    }
}
