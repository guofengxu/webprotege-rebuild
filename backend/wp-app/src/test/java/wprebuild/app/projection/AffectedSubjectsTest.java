package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;

/** Collects IRIs that must be recomputed after axiom edits (P6-12). */
class AffectedSubjectsTest {

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private final OWLDataFactory data = OWLManager.getOWLDataFactory();

    @Test
    void subclass_axiom_includes_child_and_parent() {
        IRI child = IRI.create("http://example.org/Child");
        IRI parent = IRI.create("http://example.org/Parent");
        var axiom = data.getOWLSubClassOfAxiom(data.getOWLClass(child), data.getOWLClass(parent));

        Set<IRI> subjects = AffectedSubjects.of(List.of(new AddAxiom(ONTOLOGY, axiom)));

        assertThat(subjects).containsExactlyInAnyOrder(child, parent);
    }

    @Test
    void annotation_assertion_includes_subject_iri_even_when_absent_from_signature() {
        IRI subject = IRI.create("http://example.org/Pallet");
        var axiom = data.getOWLAnnotationAssertionAxiom(data.getRDFSLabel(), subject, data.getOWLLiteral("托盘", "zh"));
        assertThat(axiom.getSignature()).extracting(e -> e.getIRI()).doesNotContain(subject);

        Set<IRI> subjects = AffectedSubjects.of(List.of(new AddAxiom(ONTOLOGY, axiom)));

        assertThat(subjects).contains(subject);
        assertThat(subjects).contains(data.getRDFSLabel().getIRI());
    }

    @Test
    void domain_and_range_axioms_include_property_and_filler() {
        IRI property = IRI.create("http://example.org/storedAt");
        IRI domain = IRI.create("http://example.org/HandlingUnit");
        IRI range = IRI.create("http://example.org/Location");
        var domainAxiom =
                data.getOWLObjectPropertyDomainAxiom(data.getOWLObjectProperty(property), data.getOWLClass(domain));
        var rangeAxiom =
                data.getOWLObjectPropertyRangeAxiom(data.getOWLObjectProperty(property), data.getOWLClass(range));

        Set<IRI> subjects = AffectedSubjects.of(
                List.of(new AddAxiom(ONTOLOGY, domainAxiom), new RemoveAxiom(ONTOLOGY, rangeAxiom)));

        assertThat(subjects).containsExactlyInAnyOrder(property, domain, range);
    }

    @Test
    void import_changes_do_not_affect_projection_subjects() {
        Set<IRI> subjects =
                AffectedSubjects.of(List.of(new AddImport(ONTOLOGY, IRI.create("http://example.org/imported"))));

        assertThat(subjects).isEmpty();
    }
}
