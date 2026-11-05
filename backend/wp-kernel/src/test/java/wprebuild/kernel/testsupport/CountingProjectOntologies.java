package wprebuild.kernel.testsupport;

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
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Counts full-project scans so an incremental update can show it did not materialise the signature
 * (P1-24). P1-25 reuses the same counters.
 */
public final class CountingProjectOntologies implements ProjectOntologies {

    private final ProjectOntologies delegate;

    private int signatureCalls;

    private int axiomsOfTypeCalls;

    public CountingProjectOntologies(ProjectOntologies delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public int signatureCalls() {
        return signatureCalls;
    }

    public int axiomsOfTypeCalls() {
        return axiomsOfTypeCalls;
    }

    public void reset() {
        signatureCalls = 0;
        axiomsOfTypeCalls = 0;
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
        axiomsOfTypeCalls++;
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
        signatureCalls++;
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
