package wprebuild.app.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.util.AxiomSubjectProviderEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import wprebuild.app.access.RoleAssignmentsChanged;
import wprebuild.app.ontology.HierarchyKind;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.RevisionCommittedEvent;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.hierarchy.AnnotationPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.hierarchy.DataPropertyHierarchyView;
import wprebuild.kernel.hierarchy.HierarchyDelta;
import wprebuild.kernel.hierarchy.HierarchyEdge;
import wprebuild.kernel.hierarchy.ObjectPropertyHierarchyView;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.shortform.DisplayNameView;

/**
 * Turns a committed revision into BS-11 events.
 *
 * <p>The listener is synchronous, so it still runs inside the generation lock (P1-11). View deltas
 * are copied here, before the lock is released. Delivery to subscribers is asynchronous.
 * {@code @EventListener} is required: an after-commit listener would run after that lock is gone.
 * A silent revision emits only {@link ProjectChanged} (BS-11).
 */
@Component
public final class EventTranslator {

    private static final Logger log = LoggerFactory.getLogger(EventTranslator.class);

    private static final List<EntityType<?>> NAMED_TYPES = List.of(
            EntityType.CLASS,
            EntityType.OBJECT_PROPERTY,
            EntityType.DATA_PROPERTY,
            EntityType.ANNOTATION_PROPERTY,
            EntityType.NAMED_INDIVIDUAL);

    private final ProjectRuntimeRegistry registry;

    private final ProjectEventHub hub;

    private final SubscriptionRevoker revoker;

    public EventTranslator(ProjectRuntimeRegistry registry, ProjectEventHub hub, SubscriptionRevoker revoker) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.revoker = Objects.requireNonNull(revoker, "revoker");
    }

    @EventListener
    public void onRevision(RevisionCommittedEvent event) {
        try {
            translate(event);
        } catch (RuntimeException ex) {
            log.error(
                    "failed to translate revision {} of {}",
                    event.revision().number().value(),
                    event.projectId(),
                    ex);
            try {
                hub.resync(event.projectId());
            } catch (RuntimeException nested) {
                log.error("failed to ask clients to resync {}", event.projectId(), nested);
            }
        }
    }

    @EventListener
    public void onRoleAssignments(RoleAssignmentsChanged event) {
        try {
            ProjectHandle handle = registry.get(event.projectId());
            // Generation lock then the hub lock, same order as a revision listener.
            handle.gate().generate(() -> {
                long revNo = handle.runtime().head().value();
                hub.publish(
                        event.projectId(),
                        List.of(new PermissionsChanged(revNo, event.projectId().toString())));
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("failed to translate role assignments for {}", event.projectId(), ex);
        }
        // Outside every lock so AccessService can query, after PermissionsChanged is queued.
        // If ACL checks fail, disconnect everyone for this project (fail closed); clients with
        // VIEW_PROJECT can reconnect.
        try {
            revoker.revoke(event.projectId());
        } catch (RuntimeException ex) {
            log.error("failed to revoke event subscriptions for {}", event.projectId(), ex);
            try {
                hub.disconnectAll(event.projectId());
            } catch (RuntimeException nested) {
                log.error("failed to force-disconnect subscribers for {}", event.projectId(), nested);
            }
        }
    }

    private void translate(RevisionCommittedEvent event) {
        Revision revision = event.revision();
        long revNo = revision.number().value();
        List<ProjectEvent> events = new ArrayList<>();
        events.add(new ProjectChanged(
                revNo,
                revision.user().value(),
                revision.timestamp(),
                revision.description(),
                revision.changes().size()));
        if (!event.silent()) {
            // Do not call registry.get here: a cache miss retires this runtime and clears lastDelta.
            ProjectRuntime runtime = registry.peek(event.projectId())
                    .orElseThrow(() -> new IllegalStateException("project runtime is not loaded"))
                    .runtime();
            appendHierarchies(events, revNo, runtime);
            appendBrowserText(events, revNo, runtime);
            appendFrames(events, revNo, revision.changes(), runtime);
        }
        hub.publish(event.projectId(), List.copyOf(events));
    }

    private static void appendHierarchies(List<ProjectEvent> events, long revNo, ProjectRuntime runtime) {
        appendHierarchy(
                events,
                revNo,
                HierarchyKind.CLASS,
                runtime.view(ClassHierarchyView.class).lastDelta());
        appendHierarchy(
                events,
                revNo,
                HierarchyKind.OBJECT_PROPERTY,
                runtime.view(ObjectPropertyHierarchyView.class).lastDelta());
        appendHierarchy(
                events,
                revNo,
                HierarchyKind.DATA_PROPERTY,
                runtime.view(DataPropertyHierarchyView.class).lastDelta());
        appendHierarchy(
                events,
                revNo,
                HierarchyKind.ANNOTATION_PROPERTY,
                runtime.view(AnnotationPropertyHierarchyView.class).lastDelta());
    }

    private static <E extends OWLEntity> void appendHierarchy(
            List<ProjectEvent> events, long revNo, HierarchyKind kind, HierarchyDelta<E> delta) {
        if (delta.added().isEmpty() && delta.removed().isEmpty()) {
            return;
        }
        events.add(new EntityHierarchyChanged(revNo, kind.path(), links(delta.added()), links(delta.removed())));
    }

    private static <E extends OWLEntity> List<HierarchyLink> links(Set<HierarchyEdge<E>> edges) {
        return edges.stream()
                .map(edge -> new HierarchyLink(
                        edge.parent().getIRI().toString(), edge.child().getIRI().toString()))
                .sorted(Comparator.comparing(HierarchyLink::parent).thenComparing(HierarchyLink::child))
                .toList();
    }

    private static void appendBrowserText(List<ProjectEvent> events, long revNo, ProjectRuntime runtime) {
        DisplayNameView names = runtime.view(DisplayNameView.class);
        for (OWLEntity entity : sorted(names.lastChanged())) {
            events.add(new BrowserTextChanged(revNo, entityOf(entity), names.displayName(entity)));
        }
    }

    private static void appendFrames(
            List<ProjectEvent> events, long revNo, List<OntologyChange> changes, ProjectRuntime runtime) {
        for (OWLEntity entity : frameSubjects(changes, runtime.ontologies())) {
            events.add(new EntityFrameChanged(revNo, entityOf(entity)));
        }
    }

    private static List<OWLEntity> frameSubjects(List<OntologyChange> changes, ProjectOntologies ontologies) {
        Set<OWLEntity> subjects = new LinkedHashSet<>();
        OWLDataFactory data = ontologies.dataFactory();
        for (OntologyChange change : changes) {
            Optional<OWLAxiom> axiom = change.axiom();
            if (axiom.isEmpty()) {
                continue;
            }
            OWLObject subject = AxiomSubjectProviderEx.getSubject(axiom.get());
            if (subject instanceof OWLEntity entity) {
                subjects.add(entity);
            } else if (subject instanceof IRI iri) {
                addNamedAt(data, ontologies, changes, iri, subjects);
            }
        }
        return sorted(subjects);
    }

    private static void addNamedAt(
            OWLDataFactory data,
            ProjectOntologies ontologies,
            List<OntologyChange> changes,
            IRI iri,
            Set<OWLEntity> subjects) {
        for (EntityType<?> type : NAMED_TYPES) {
            OWLEntity entity = data.getOWLEntity(asEntityType(type), iri);
            if (ontologies.containsEntity(entity) || mentioned(changes, entity)) {
                subjects.add(entity);
            }
        }
    }

    private static boolean mentioned(List<OntologyChange> changes, OWLEntity entity) {
        for (OntologyChange change : changes) {
            if (change.signature().contains(entity)) {
                return true;
            }
        }
        return false;
    }

    private static List<OWLEntity> sorted(Collection<OWLEntity> entities) {
        return entities.stream()
                .sorted(Comparator.comparing(
                                (OWLEntity entity) -> entity.getIRI().toString())
                        .thenComparing(entity -> entity.getEntityType().getName()))
                .toList();
    }

    private static EventEntity entityOf(OWLEntity entity) {
        return new EventEntity(entity.getIRI().toString(), entityType(entity));
    }

    private static String entityType(OWLEntity entity) {
        return switch (entity) {
            case OWLClass ignored -> "CLASS";
            case OWLObjectProperty ignored -> "OBJECT_PROPERTY";
            case OWLDataProperty ignored -> "DATA_PROPERTY";
            case OWLAnnotationProperty ignored -> "ANNOTATION_PROPERTY";
            case OWLNamedIndividual ignored -> "NAMED_INDIVIDUAL";
            case OWLDatatype ignored -> "DATATYPE";
            default -> entity.getEntityType().getName();
        };
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> asEntityType(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }
}
