package wprebuild.kernel.ontology;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.model.parameters.Imports;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;

/**
 * {@link MutableProjectOntologies} backed by one concurrent OWL ontology per {@link OntologyKey}.
 * The key map is owned here; anonymous ontologies are not looked up by {@link OWLOntologyID}.
 */
public final class OwlApiProjectOntologies implements MutableProjectOntologies {

    private final OWLOntologyManager manager;

    private final ConcurrentMap<OntologyKey, Slot> byKey = new ConcurrentHashMap<>();

    private OwlApiProjectOntologies(OWLOntologyManager manager) {
        this.manager = manager;
        // AddImport records a declaration only. SILENT stops a missing import from being fetched.
        manager.getOntologyConfigurator().setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
    }

    public static OwlApiProjectOntologies empty() {
        return new OwlApiProjectOntologies(OWLManager.createConcurrentOWLOntologyManager());
    }

    @Override
    public synchronized void apply(List<OntologyChange> changes) {
        Objects.requireNonNull(changes, "changes");
        for (OntologyChange change : List.copyOf(changes)) {
            applyOne(change);
        }
    }

    @Override
    public Set<OntologyKey> keys() {
        return Set.copyOf(byKey.keySet());
    }

    @Override
    public Optional<OntologyInfo> info(OntologyKey key) {
        Objects.requireNonNull(key, "ontology");
        Slot slot = byKey.get(key);
        if (slot == null) {
            return Optional.empty();
        }
        return Optional.of(slot.info());
    }

    @Override
    public boolean containsAxiom(OntologyKey key, OWLAxiom axiom) {
        Objects.requireNonNull(axiom, "axiom");
        return requireSlot(key)
                .ontology()
                .containsAxiom(axiom, Imports.EXCLUDED, AxiomAnnotations.CONSIDER_AXIOM_ANNOTATIONS);
    }

    @Override
    public boolean containsAnnotation(OntologyKey key, OWLAnnotation annotation) {
        Objects.requireNonNull(annotation, "annotation");
        return requireSlot(key).ontology().getAnnotations().contains(annotation);
    }

    @Override
    public boolean containsImport(OntologyKey key, IRI iri) {
        Objects.requireNonNull(iri, "iri");
        return requireSlot(key).ontology().getDirectImportsDocuments().contains(iri);
    }

    @Override
    public Stream<OWLAxiom> axioms(OntologyKey key) {
        return requireSlot(key).ontology().axioms(Imports.EXCLUDED);
    }

    @Override
    public Set<OWLAnnotation> annotations(OntologyKey key) {
        return Set.copyOf(requireSlot(key).ontology().getAnnotations());
    }

    @Override
    public Set<IRI> imports(OntologyKey key) {
        return Set.copyOf(requireSlot(key).ontology().getDirectImportsDocuments());
    }

    @Override
    public <T extends OWLAxiom> Stream<T> axiomsOfType(AxiomType<T> type) {
        Objects.requireNonNull(type, "type");
        return slots().flatMap(slot -> slot.ontology().axioms(type, Imports.EXCLUDED));
    }

    @Override
    public Stream<OWLAxiom> referencingAxioms(OWLEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return slots().flatMap(slot -> slot.ontology().referencingAxioms(entity));
    }

    @Override
    public Stream<OWLAnnotationAssertionAxiom> annotationAssertions(IRI subject) {
        Objects.requireNonNull(subject, "subject");
        return slots().flatMap(slot -> slot.ontology().annotationAssertionAxioms(subject));
    }

    @Override
    public boolean containsEntity(OWLEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return slots().anyMatch(slot -> slot.ontology().containsEntityInSignature(entity));
    }

    @Override
    public Stream<OWLEntity> signature() {
        return slots().flatMap(slot -> slot.ontology().signature(Imports.EXCLUDED))
                .distinct();
    }

    @Override
    public long axiomCount() {
        return slots().mapToLong(slot -> slot.ontology().getAxiomCount()).sum();
    }

    @Override
    public OWLDataFactory dataFactory() {
        return manager.getOWLDataFactory();
    }

    private void applyOne(OntologyChange change) {
        switch (change) {
            case DeclareOntology declare -> declareOntology(declare);
            case AddAxiom add ->
                applyOwl(new org.semanticweb.owlapi.model.AddAxiom(requireOntology(add.ontology()), add.owlAxiom()));
            case RemoveAxiom remove ->
                applyOwl(new org.semanticweb.owlapi.model.RemoveAxiom(
                        requireOntology(remove.ontology()), remove.owlAxiom()));
            case AddOntologyAnnotation add ->
                applyOwl(new org.semanticweb.owlapi.model.AddOntologyAnnotation(
                        requireOntology(add.ontology()), add.annotation()));
            case RemoveOntologyAnnotation remove ->
                applyOwl(new org.semanticweb.owlapi.model.RemoveOntologyAnnotation(
                        requireOntology(remove.ontology()), remove.annotation()));
            case AddImport add ->
                applyOwl(new org.semanticweb.owlapi.model.AddImport(
                        requireOntology(add.ontology()), importDeclaration(add.iri())));
            case RemoveImport remove ->
                applyOwl(new org.semanticweb.owlapi.model.RemoveImport(
                        requireOntology(remove.ontology()), importDeclaration(remove.iri())));
        }
    }

    private void declareOntology(DeclareOntology change) {
        OntologyKey key = change.ontology();
        if (byKey.containsKey(key)) {
            throw new IllegalStateException("ontology is already registered: " + key.value());
        }
        OntologyInfo info = new OntologyInfo(key, change.ontologyIri(), change.versionIri());
        byKey.put(key, new Slot(info, createOntology(change)));
    }

    private OWLOntology createOntology(DeclareOntology change) {
        try {
            if (change.ontologyIri().isEmpty()) {
                return manager.createOntology();
            }
            return manager.createOntology(new OWLOntologyID(change.ontologyIri(), change.versionIri()));
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalStateException(
                    "failed to create ontology " + change.ontology().value(), ex);
        }
    }

    private void applyOwl(org.semanticweb.owlapi.model.OWLOntologyChange change) {
        manager.applyChange(change);
    }

    private OWLImportsDeclaration importDeclaration(IRI iri) {
        return dataFactory().getOWLImportsDeclaration(iri);
    }

    private OWLOntology requireOntology(OntologyKey key) {
        return requireSlot(key).ontology();
    }

    private Slot requireSlot(OntologyKey key) {
        Objects.requireNonNull(key, "ontology");
        Slot slot = byKey.get(key);
        if (slot == null) {
            throw new IllegalStateException("ontology is not registered: " + key.value());
        }
        return slot;
    }

    private Stream<Slot> slots() {
        return byKey.values().stream();
    }

    private record Slot(OntologyInfo info, OWLOntology ontology) {}
}
