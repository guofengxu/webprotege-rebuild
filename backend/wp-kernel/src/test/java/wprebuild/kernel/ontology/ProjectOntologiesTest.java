package wprebuild.kernel.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.testsupport.TestOntologies;

class ProjectOntologiesTest {

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final OntologyKey MISSING = OntologyKey.of(9);

    private static final IRI FIRST_IRI = IRI.create("http://example.org/wp-test/first");

    private static final IRI FIRST_VERSION = IRI.create("http://example.org/wp-test/first/1");

    private static final IRI PERSON_IRI = IRI.create("http://example.org/wp-test/Person");

    private static final IRI THING_IRI = IRI.create("http://example.org/wp-test/Thing");

    private static final IRI AGENT_IRI = IRI.create("http://example.org/wp-test/Agent");

    private static final IRI IMPORTED = IRI.create("http://example.org/wp-test/imported");

    private static final IRI UNRESOLVED = IRI.create("http://invalid.invalid/x");

    @Test
    void registers_two_ontologies_and_queries_each_change_kind() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        OWLDataFactory dataFactory = ontologies.dataFactory();
        OWLClass person = dataFactory.getOWLClass(PERSON_IRI);
        OWLClass thing = dataFactory.getOWLClass(THING_IRI);
        OWLClass agent = dataFactory.getOWLClass(AGENT_IRI);
        OWLSubClassOfAxiom personSubclass = dataFactory.getOWLSubClassOfAxiom(person, thing);
        OWLSubClassOfAxiom agentSubclass = dataFactory.getOWLSubClassOfAxiom(person, agent);
        OWLAnnotationAssertionAxiom label = dataFactory.getOWLAnnotationAssertionAxiom(
                dataFactory.getRDFSLabel(), PERSON_IRI, dataFactory.getOWLLiteral("人", "zh"));
        OWLAnnotation comment =
                dataFactory.getOWLAnnotation(dataFactory.getRDFSComment(), dataFactory.getOWLLiteral("note"));

        assertThat(ontologies.keys()).isEmpty();
        assertThat(ontologies.axiomCount()).isZero();
        assertThat(ontologies.info(MISSING)).isEmpty();

        ontologies.apply(List.of(
                new DeclareOntology(FIRST, Optional.of(FIRST_IRI), Optional.of(FIRST_VERSION)),
                new DeclareOntology(SECOND, Optional.empty(), Optional.empty()),
                new AddAxiom(FIRST, personSubclass),
                new AddAxiom(FIRST, label),
                new AddAxiom(SECOND, agentSubclass),
                new AddOntologyAnnotation(FIRST, comment),
                new AddImport(FIRST, IMPORTED)));

        assertThat(ontologies.keys()).containsExactlyInAnyOrder(FIRST, SECOND);
        assertThat(ontologies.info(FIRST))
                .contains(new OntologyInfo(FIRST, Optional.of(FIRST_IRI), Optional.of(FIRST_VERSION)));
        assertThat(ontologies.info(SECOND)).contains(new OntologyInfo(SECOND, Optional.empty(), Optional.empty()));

        assertThat(ontologies.containsAxiom(FIRST, personSubclass)).isTrue();
        assertThat(ontologies.containsAxiom(FIRST, label)).isTrue();
        assertThat(ontologies.containsAxiom(SECOND, personSubclass)).isFalse();
        assertThat(ontologies.containsAxiom(SECOND, agentSubclass)).isTrue();
        assertThat(ontologies.containsAnnotation(FIRST, comment)).isTrue();
        assertThat(ontologies.containsAnnotation(SECOND, comment)).isFalse();
        assertThat(ontologies.containsImport(FIRST, IMPORTED)).isTrue();
        assertThat(ontologies.containsImport(SECOND, IMPORTED)).isFalse();

        assertThat(ontologies.axioms(FIRST)).containsExactlyInAnyOrder(personSubclass, label);
        assertThat(ontologies.axioms(SECOND)).containsExactly(agentSubclass);
        assertThat(ontologies.annotations(FIRST)).containsExactly(comment);
        assertThat(ontologies.annotations(SECOND)).isEmpty();
        assertThat(ontologies.imports(FIRST)).containsExactly(IMPORTED);
        assertThat(ontologies.imports(SECOND)).isEmpty();

        assertThat(ontologies.axiomsOfType(AxiomType.SUBCLASS_OF))
                .containsExactlyInAnyOrder(personSubclass, agentSubclass);
        assertThat(ontologies.referencingAxioms(person)).containsExactlyInAnyOrder(personSubclass, agentSubclass);
        assertThat(ontologies.annotationAssertions(PERSON_IRI)).containsExactly(label);
        assertThat(ontologies.containsEntity(person)).isTrue();
        assertThat(ontologies.containsEntity(dataFactory.getOWLClass(IRI.create("http://example.org/wp-test/Other"))))
                .isFalse();
        assertThat(ontologies.signature().filter(person::equals)).containsExactly(person);
        assertThat(ontologies.axiomCount()).isEqualTo(3);

        ontologies.apply(List.of(
                new RemoveAxiom(FIRST, personSubclass),
                new RemoveAxiom(FIRST, label),
                new RemoveAxiom(SECOND, agentSubclass),
                new RemoveOntologyAnnotation(FIRST, comment),
                new RemoveImport(FIRST, IMPORTED)));

        assertThat(ontologies.containsAxiom(FIRST, personSubclass)).isFalse();
        assertThat(ontologies.containsAnnotation(FIRST, comment)).isFalse();
        assertThat(ontologies.containsImport(FIRST, IMPORTED)).isFalse();
        assertThat(ontologies.axiomCount()).isZero();
        assertThat(ontologies.annotations(FIRST)).isEmpty();
        assertThat(ontologies.imports(FIRST)).isEmpty();
        assertThat(ontologies.keys()).containsExactlyInAnyOrder(FIRST, SECOND);
    }

    @Test
    void axiom_count_matches_the_corpus_after_adding_every_axiom() {
        OWLOntology source = TestOntologies.load("axioms-all-types.ofn");
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        List<OntologyChange> changes = new ArrayList<>();
        changes.add(new DeclareOntology(
                FIRST,
                source.getOntologyID().getOntologyIRI(),
                source.getOntologyID().getVersionIRI()));
        for (OWLAxiom axiom : source.getAxioms()) {
            changes.add(new AddAxiom(FIRST, axiom));
        }

        ontologies.apply(changes);

        assertThat(ontologies.axiomCount()).isEqualTo(source.getAxiomCount());
        assertThat(ontologies.axioms(FIRST)).containsExactlyInAnyOrderElementsOf(source.getAxioms());
    }

    @Test
    void rejects_changes_and_queries_for_an_unregistered_key() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        OWLDataFactory dataFactory = ontologies.dataFactory();
        OWLAxiom axiom = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(PERSON_IRI));
        OWLAnnotation annotation =
                dataFactory.getOWLAnnotation(dataFactory.getRDFSLabel(), dataFactory.getOWLLiteral("x"));
        List<OntologyChange> changes = List.of(
                new AddAxiom(MISSING, axiom),
                new RemoveAxiom(MISSING, axiom),
                new AddOntologyAnnotation(MISSING, annotation),
                new RemoveOntologyAnnotation(MISSING, annotation),
                new AddImport(MISSING, IMPORTED),
                new RemoveImport(MISSING, IMPORTED));

        for (OntologyChange change : changes) {
            assertThatThrownBy(() -> ontologies.apply(List.of(change)))
                    .as("unregistered key in %s", change.getClass().getSimpleName())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(MISSING.value());
        }
        assertThatThrownBy(() -> ontologies.containsAxiom(MISSING, axiom)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ontologies.containsAnnotation(MISSING, annotation))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ontologies.containsImport(MISSING, IMPORTED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ontologies.axioms(MISSING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ontologies.annotations(MISSING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ontologies.imports(MISSING)).isInstanceOf(IllegalStateException.class);
        assertThat(ontologies.info(MISSING)).isEmpty();
        assertThat(ontologies.keys()).isEmpty();
    }

    @Test
    void rejects_a_second_declaration_of_the_same_key() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(new DeclareOntology(FIRST, Optional.empty(), Optional.empty())));

        assertThatThrownBy(() ->
                        ontologies.apply(List.of(new DeclareOntology(FIRST, Optional.of(FIRST_IRI), Optional.empty()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FIRST.value());
        assertThat(ontologies.info(FIRST)).contains(new OntologyInfo(FIRST, Optional.empty(), Optional.empty()));
    }

    @Test
    void records_an_unresolved_import_without_loading_the_document() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(new DeclareOntology(FIRST, Optional.empty(), Optional.empty())));

        long started = System.nanoTime();
        ontologies.apply(List.of(new AddImport(FIRST, UNRESOLVED)));
        long elapsed = System.nanoTime() - started;

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1).toNanos());
        assertThat(ontologies.containsImport(FIRST, UNRESOLVED)).isTrue();
        assertThat(ontologies.imports(FIRST)).containsExactly(UNRESOLVED);
        assertThat(ontologies.axiomCount()).isZero();
        assertThat(ontologies.keys()).containsExactly(FIRST);
    }

    @Test
    void applies_redundant_add_and_remove_in_order() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        OWLDataFactory dataFactory = ontologies.dataFactory();
        OWLAxiom axiom = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(PERSON_IRI));
        ontologies.apply(List.of(new DeclareOntology(FIRST, Optional.empty(), Optional.empty())));

        ontologies.apply(
                List.of(new RemoveAxiom(FIRST, axiom), new AddAxiom(FIRST, axiom), new AddAxiom(FIRST, axiom)));

        assertThat(ontologies.containsAxiom(FIRST, axiom)).isTrue();
        assertThat(ontologies.axiomCount()).isEqualTo(1);

        ontologies.apply(List.of(new RemoveAxiom(FIRST, axiom), new RemoveAxiom(FIRST, axiom)));

        assertThat(ontologies.containsAxiom(FIRST, axiom)).isFalse();
        assertThat(ontologies.axiomCount()).isZero();
    }

    @Test
    void rejects_null_arguments() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(new DeclareOntology(FIRST, Optional.empty(), Optional.empty())));

        assertThatThrownBy(() -> ontologies.apply(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ontologies.info(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ontologies.axiomsOfType(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ontologies.referencingAxioms(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ontologies.annotationAssertions(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ontologies.containsEntity(null)).isInstanceOf(NullPointerException.class);
    }
}
