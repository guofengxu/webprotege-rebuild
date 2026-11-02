package wprebuild.kernel.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import wprebuild.kernel.testsupport.TestOntologies;

class OntologyChangeTest {

    private static final OntologyKey KEY = OntologyKey.of(1);

    private static final IRI ONTOLOGY_IRI = IRI.create("http://example.org/wp-test/ont");

    private static final IRI VERSION_IRI = IRI.create("http://example.org/wp-test/ont/1");

    private static final IRI IMPORTED = IRI.create("http://example.org/wp-test/imported");

    @Test
    void ontology_key_accepts_the_canonical_form() {
        assertThat(new OntologyKey("o1").value()).isEqualTo("o1");
        assertThat(new OntologyKey("o10")).isEqualTo(OntologyKey.of(10));
        assertThat(OntologyKey.of(1).value()).isEqualTo("o1");
    }

    @Test
    void ontology_key_rejects_a_value_outside_the_pattern() {
        assertThatThrownBy(() -> new OntologyKey("o0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("o0");
        assertThatThrownBy(() -> new OntologyKey("o01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OntologyKey("O1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OntologyKey("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OntologyKey.of(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
        assertThatThrownBy(() -> OntologyKey.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ontology_key_rejects_null() {
        assertThatThrownBy(() -> new OntologyKey(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void add_axiom_round_trips_and_keeps_the_axiom_signature() {
        OWLOntology ontology = TestOntologies.load("axioms-all-types.ofn");

        assertThat(ontology.getAxioms()).isNotEmpty();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            AddAxiom change = new AddAxiom(KEY, axiom);

            assertThat(change.ontology()).isEqualTo(KEY);
            assertThat(change.isAxiomChange()).isTrue();
            assertThat(change.axiom()).contains(axiom);
            assertThat(change.signature()).isEqualTo(axiom.getSignature());
            assertThat(change.inverse()).isEqualTo(new RemoveAxiom(KEY, axiom));
            assertThat(change.inverse().inverse()).as("round trip %s", axiom).isEqualTo(change);
            assertThat(change.inverse().signature()).isEqualTo(axiom.getSignature());
            assertThat(change.inverse().isAxiomChange()).isTrue();
            assertThat(change.inverse().axiom()).contains(axiom);
        }
    }

    @Test
    void annotation_and_import_changes_invert_and_expose_their_signature() {
        OWLDataFactory dataFactory = TestOntologies.load("axioms-all-types.ofn")
                .getOWLOntologyManager()
                .getOWLDataFactory();
        OWLAnnotation annotation =
                dataFactory.getOWLAnnotation(dataFactory.getRDFSLabel(), dataFactory.getOWLLiteral("人", "zh"));

        AddOntologyAnnotation addAnnotation = new AddOntologyAnnotation(KEY, annotation);
        assertThat(addAnnotation.isAxiomChange()).isFalse();
        assertThat(addAnnotation.axiom()).isEmpty();
        assertThat(addAnnotation.signature())
                .isEqualTo(annotation.getSignature())
                .isNotEmpty();
        assertThat(addAnnotation.inverse()).isEqualTo(new RemoveOntologyAnnotation(KEY, annotation));
        assertThat(addAnnotation.inverse().inverse()).isEqualTo(addAnnotation);
        assertThat(addAnnotation.inverse().signature()).isEqualTo(annotation.getSignature());

        AddImport addImport = new AddImport(KEY, IMPORTED);
        assertThat(addImport.isAxiomChange()).isFalse();
        assertThat(addImport.axiom()).isEmpty();
        assertThat(addImport.signature()).isEmpty();
        assertThat(addImport.inverse()).isEqualTo(new RemoveImport(KEY, IMPORTED));
        assertThat(addImport.inverse().inverse()).isEqualTo(addImport);
        assertThat(addImport.inverse().signature()).isEmpty();
    }

    @Test
    void declare_ontology_accepts_an_anonymous_ontology_and_an_iri_without_a_version() {
        DeclareOntology anonymous = new DeclareOntology(KEY, Optional.empty(), Optional.empty());
        DeclareOntology named = new DeclareOntology(KEY, Optional.of(ONTOLOGY_IRI), Optional.empty());
        DeclareOntology versioned = new DeclareOntology(KEY, Optional.of(ONTOLOGY_IRI), Optional.of(VERSION_IRI));

        assertThat(anonymous.signature()).isEmpty();
        assertThat(anonymous.isAxiomChange()).isFalse();
        assertThat(anonymous.axiom()).isEmpty();
        assertThat(named.ontologyIri()).contains(ONTOLOGY_IRI);
        assertThat(named.versionIri()).isEmpty();
        assertThat(versioned.versionIri()).contains(VERSION_IRI);
    }

    @Test
    void declare_ontology_rejects_a_version_iri_without_an_ontology_iri() {
        assertThatThrownBy(() -> new DeclareOntology(KEY, Optional.empty(), Optional.of(VERSION_IRI)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void declare_ontology_cannot_be_inverted() {
        DeclareOntology declared = new DeclareOntology(KEY, Optional.of(ONTOLOGY_IRI), Optional.of(VERSION_IRI));

        assertThatThrownBy(declared::inverse).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changes_reject_null_parts() {
        assertThatThrownBy(() -> new AddAxiom(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AddAxiom(KEY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RemoveAxiom(KEY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AddOntologyAnnotation(KEY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RemoveOntologyAnnotation(KEY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AddImport(KEY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RemoveImport(KEY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclareOntology(null, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclareOntology(KEY, null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclareOntology(KEY, Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
