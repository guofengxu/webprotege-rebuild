package wprebuild.kernel.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.Revision;

/**
 * In-memory project: ontologies, head revision, and derived views (ADR-005, BS-03).
 *
 * <p>External callers see a read-only {@link ProjectOntologies}. {@link #applyCommitted(Revision)}
 * is package-private for the write pipeline: it applies the stored changes, advances the head, then
 * notifies views in registration order.
 */
public final class ProjectRuntime {

    private final ProjectId projectId;

    private final MutableProjectOntologies ontologies;

    private final ProjectOntologies readable;

    private final List<DerivedView> views;

    private RevisionNumber head;

    /** WP-8: set when applying a committed revision to memory fails. Sticky until reload. */
    private volatile boolean corrupted;

    ProjectRuntime(
            ProjectId projectId, MutableProjectOntologies ontologies, RevisionNumber head, List<DerivedView> views) {
        this.projectId = Objects.requireNonNull(projectId, "project id");
        this.ontologies = Objects.requireNonNull(ontologies, "ontologies");
        this.readable = new ReadOnlyProjectOntologies(this.ontologies);
        this.head = Objects.requireNonNull(head, "head");
        this.views = List.copyOf(Objects.requireNonNull(views, "views"));
    }

    public ProjectOntologies ontologies() {
        return readable;
    }

    public RevisionNumber head() {
        return head;
    }

    /** WP-8: further writes are refused after memory apply fails. */
    public boolean isCorrupted() {
        return corrupted;
    }

    public <V extends DerivedView> V view(Class<V> type) {
        Objects.requireNonNull(type, "view type");
        V match = null;
        for (DerivedView view : views) {
            if (!type.isInstance(view)) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException("more than one derived view of type " + type.getName());
            }
            match = type.cast(view);
        }
        if (match == null) {
            throw new IllegalArgumentException("no derived view of type " + type.getName());
        }
        return match;
    }

    ProjectId projectId() {
        return projectId;
    }

    /**
     * Applies {@code revision} as stored, sets the head to its number, then notifies each view.
     *
     * <p>The change list is already effective, so it is not filtered again.
     *
     * @throws IllegalArgumentException if {@code revision} is not the successor of the current head
     */
    void applyCommitted(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        RevisionNumber required = head.next();
        if (!revision.number().equals(required)) {
            throw new IllegalArgumentException("revision number must be %s, not %s"
                    .formatted(required.value(), revision.number().value()));
        }
        ontologies.apply(revision.changes());
        head = revision.number();
        for (DerivedView view : views) {
            view.onApplied(revision.changes(), readable);
        }
    }

    /**
     * WP-8: memory no longer matches the log. The write pipeline calls this when apply fails, and
     * the registry calls it when dropping a runtime so the old pipeline stops writing (ADR-006).
     * The flag stays set.
     */
    public void markCorrupted() {
        corrupted = true;
    }

    /** Stops callers from casting {@link ProjectRuntime#ontologies()} back to a mutable model. */
    private static final class ReadOnlyProjectOntologies implements ProjectOntologies {

        private final ProjectOntologies delegate;

        private ReadOnlyProjectOntologies(ProjectOntologies delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<OntologyKey> keys() {
            return delegate.keys();
        }

        @Override
        public Optional<OntologyInfo> info(OntologyKey key) {
            return delegate.info(key);
        }

        @Override
        public boolean containsAxiom(OntologyKey key, OWLAxiom axiom) {
            return delegate.containsAxiom(key, axiom);
        }

        @Override
        public boolean containsAnnotation(OntologyKey key, OWLAnnotation annotation) {
            return delegate.containsAnnotation(key, annotation);
        }

        @Override
        public boolean containsImport(OntologyKey key, IRI iri) {
            return delegate.containsImport(key, iri);
        }

        @Override
        public Stream<OWLAxiom> axioms(OntologyKey key) {
            return delegate.axioms(key);
        }

        @Override
        public Set<OWLAnnotation> annotations(OntologyKey key) {
            return delegate.annotations(key);
        }

        @Override
        public Set<IRI> imports(OntologyKey key) {
            return delegate.imports(key);
        }

        @Override
        public <T extends OWLAxiom> Stream<T> axiomsOfType(AxiomType<T> type) {
            return delegate.axiomsOfType(type);
        }

        @Override
        public Stream<OWLAxiom> referencingAxioms(OWLEntity entity) {
            return delegate.referencingAxioms(entity);
        }

        @Override
        public Stream<OWLAnnotationAssertionAxiom> annotationAssertions(IRI subject) {
            return delegate.annotationAssertions(subject);
        }

        @Override
        public boolean containsEntity(OWLEntity entity) {
            return delegate.containsEntity(entity);
        }

        @Override
        public Stream<OWLEntity> signature() {
            return delegate.signature();
        }

        @Override
        public long axiomCount() {
            return delegate.axiomCount();
        }

        @Override
        public OWLDataFactory dataFactory() {
            return delegate.dataFactory();
        }
    }
}
