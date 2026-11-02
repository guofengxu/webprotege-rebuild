package wprebuild.kernel.ontology;

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

/**
 * In-memory ontologies of one project (ADR-005).
 *
 * <p>Methods that take an {@link OntologyKey} throw {@link IllegalStateException} when that key is
 * not registered. {@link #info(OntologyKey)} returns empty instead. Queries do not follow
 * {@code owl:imports} and do not build an index beyond OWL API.
 */
public interface ProjectOntologies {

    Set<OntologyKey> keys();

    Optional<OntologyInfo> info(OntologyKey key);

    boolean containsAxiom(OntologyKey key, OWLAxiom axiom);

    boolean containsAnnotation(OntologyKey key, OWLAnnotation annotation);

    boolean containsImport(OntologyKey key, IRI iri);

    Stream<OWLAxiom> axioms(OntologyKey key);

    Set<OWLAnnotation> annotations(OntologyKey key);

    Set<IRI> imports(OntologyKey key);

    <T extends OWLAxiom> Stream<T> axiomsOfType(AxiomType<T> type);

    Stream<OWLAxiom> referencingAxioms(OWLEntity entity);

    Stream<OWLAnnotationAssertionAxiom> annotationAssertions(IRI subject);

    boolean containsEntity(OWLEntity entity);

    Stream<OWLEntity> signature();

    long axiomCount();

    OWLDataFactory dataFactory();
}
