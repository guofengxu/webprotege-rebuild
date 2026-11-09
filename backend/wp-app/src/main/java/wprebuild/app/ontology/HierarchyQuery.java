package wprebuild.app.ontology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.hierarchy.AnnotationPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.hierarchy.DataPropertyHierarchyView;
import wprebuild.kernel.hierarchy.HierarchyView;
import wprebuild.kernel.hierarchy.ObjectPropertyHierarchyView;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.shortform.DisplayNameView;

/**
 * Reads one hierarchy from the loaded runtime while holding the project read lock (ADR-006).
 *
 * <p>Roots and children are ordered by display name, case-insensitively (BS-09). An IRI that is not
 * in the hierarchy produces an empty list.
 */
@Component
final class HierarchyQuery {

    private static final Comparator<EntityNode> BY_DISPLAY_NAME = Comparator.comparing(
                    EntityNode::displayName, String::compareToIgnoreCase)
            .thenComparing(EntityNode::iri);

    private final ProjectRuntimeRegistry registry;

    HierarchyQuery(ProjectRuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    List<EntityNode> roots(ProjectId projectId, HierarchyKind kind) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(kind, "kind");
        return read(projectId, runtime -> roots(runtime, kind));
    }

    List<EntityNode> children(ProjectId projectId, HierarchyKind kind, IRI parent) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(parent, "parent");
        return read(projectId, runtime -> children(runtime, kind, parent));
    }

    List<List<EntityNode>> paths(ProjectId projectId, HierarchyKind kind, IRI entity) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entity, "entity");
        return read(projectId, runtime -> paths(runtime, kind, entity));
    }

    private <T> T read(ProjectId projectId, Function<ProjectRuntime, T> query) {
        ProjectHandle handle = registry.get(projectId);
        return handle.gate().read(() -> {
            if (handle.runtime().isCorrupted()) {
                throw new IllegalStateException("project runtime is corrupted: " + projectId);
            }
            return query.apply(handle.runtime());
        });
    }

    private static List<EntityNode> roots(ProjectRuntime runtime, HierarchyKind kind) {
        return switch (kind) {
            case CLASS -> render(runtime, runtime.view(ClassHierarchyView.class));
            case OBJECT_PROPERTY -> render(runtime, runtime.view(ObjectPropertyHierarchyView.class));
            case DATA_PROPERTY -> render(runtime, runtime.view(DataPropertyHierarchyView.class));
            case ANNOTATION_PROPERTY -> render(runtime, runtime.view(AnnotationPropertyHierarchyView.class));
        };
    }

    private static <E extends OWLEntity> List<EntityNode> render(ProjectRuntime runtime, HierarchyView<E> view) {
        return render(runtime, view, view.roots());
    }

    private static List<EntityNode> children(ProjectRuntime runtime, HierarchyKind kind, IRI parent) {
        OWLDataFactory data = runtime.ontologies().dataFactory();
        return switch (kind) {
            case CLASS -> {
                ClassHierarchyView view = runtime.view(ClassHierarchyView.class);
                yield render(runtime, view, view.children(data.getOWLClass(parent)));
            }
            case OBJECT_PROPERTY -> {
                ObjectPropertyHierarchyView view = runtime.view(ObjectPropertyHierarchyView.class);
                yield render(runtime, view, view.children(data.getOWLObjectProperty(parent)));
            }
            case DATA_PROPERTY -> {
                DataPropertyHierarchyView view = runtime.view(DataPropertyHierarchyView.class);
                yield render(runtime, view, view.children(data.getOWLDataProperty(parent)));
            }
            case ANNOTATION_PROPERTY -> {
                AnnotationPropertyHierarchyView view = runtime.view(AnnotationPropertyHierarchyView.class);
                yield render(runtime, view, view.children(data.getOWLAnnotationProperty(parent)));
            }
        };
    }

    private static List<List<EntityNode>> paths(ProjectRuntime runtime, HierarchyKind kind, IRI entity) {
        OWLDataFactory data = runtime.ontologies().dataFactory();
        return switch (kind) {
            case CLASS -> {
                ClassHierarchyView view = runtime.view(ClassHierarchyView.class);
                yield paths(runtime, view, data.getOWLClass(entity));
            }
            case OBJECT_PROPERTY -> {
                ObjectPropertyHierarchyView view = runtime.view(ObjectPropertyHierarchyView.class);
                yield paths(runtime, view, data.getOWLObjectProperty(entity));
            }
            case DATA_PROPERTY -> {
                DataPropertyHierarchyView view = runtime.view(DataPropertyHierarchyView.class);
                yield paths(runtime, view, data.getOWLDataProperty(entity));
            }
            case ANNOTATION_PROPERTY -> {
                AnnotationPropertyHierarchyView view = runtime.view(AnnotationPropertyHierarchyView.class);
                yield paths(runtime, view, data.getOWLAnnotationProperty(entity));
            }
        };
    }

    private static <E extends OWLEntity> List<EntityNode> render(
            ProjectRuntime runtime, HierarchyView<E> view, Collection<E> entities) {
        DisplayNameView names = runtime.view(DisplayNameView.class);
        List<EntityNode> nodes = new ArrayList<>(entities.size());
        for (E entity : entities) {
            nodes.add(node(runtime, view, names, entity));
        }
        nodes.sort(BY_DISPLAY_NAME);
        return List.copyOf(nodes);
    }

    private static <E extends OWLEntity> List<List<EntityNode>> paths(
            ProjectRuntime runtime, HierarchyView<E> view, E entity) {
        DisplayNameView names = runtime.view(DisplayNameView.class);
        List<List<EntityNode>> rendered = new ArrayList<>();
        for (List<E> path : view.pathsToRoot(entity)) {
            List<EntityNode> steps = new ArrayList<>(path.size());
            for (E step : path) {
                steps.add(node(runtime, view, names, step));
            }
            rendered.add(List.copyOf(steps));
        }
        return List.copyOf(rendered);
    }

    private static <E extends OWLEntity> EntityNode node(
            ProjectRuntime runtime, HierarchyView<E> view, DisplayNameView names, E entity) {
        return new EntityNode(
                entity.getIRI().toString(),
                entityType(entity),
                names.displayName(entity),
                view.hasChildren(entity),
                deprecated(runtime.ontologies(), entity));
    }

    private static String entityType(OWLEntity entity) {
        return switch (entity) {
            case OWLClass ignored -> "CLASS";
            case OWLObjectProperty ignored -> "OBJECT_PROPERTY";
            case OWLDataProperty ignored -> "DATA_PROPERTY";
            case OWLAnnotationProperty ignored -> "ANNOTATION_PROPERTY";
            case OWLNamedIndividual ignored -> "NAMED_INDIVIDUAL";
            case OWLDatatype ignored -> "DATATYPE";
            default -> throw new IllegalArgumentException("unsupported entity " + entity.getIRI());
        };
    }

    /** {@code owl:deprecated true} stored as a boolean literal. */
    private static boolean deprecated(ProjectOntologies ontologies, OWLEntity entity) {
        return ontologies
                .annotationAssertions(entity.getIRI())
                .anyMatch(OWLAnnotationAssertionAxiom::isDeprecatedIRIAssertion);
    }
}
