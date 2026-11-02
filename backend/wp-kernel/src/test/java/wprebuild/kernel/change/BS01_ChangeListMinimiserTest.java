package wprebuild.kernel.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;

/**
 * BS-01, ported from {@code ChangeListMinimiser_TestCase} plus the specification table.
 *
 * <p>Duplicate adds or removes of one key do not stack: the legacy two-pass scan keeps a set, so
 * two adds followed by two removes leave the first removal.
 */
class BS01_ChangeListMinimiserTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private static final OntologyKey OTHER_ONTOLOGY = OntologyKey.of(2);

    private static final OWLClass CLASS_A = DATA.getOWLClass(IRI.create("http://example.org/A"));

    private static final OWLClass CLASS_B = DATA.getOWLClass(IRI.create("http://example.org/B"));

    private static final OWLClass CLASS_C = DATA.getOWLClass(IRI.create("http://example.org/C"));

    private static final OWLAxiom AXIOM_A = DATA.getOWLSubClassOfAxiom(CLASS_A, CLASS_B);

    private static final OWLAxiom AXIOM_A_COPY = DATA.getOWLSubClassOfAxiom(CLASS_A, CLASS_B);

    private static final OWLAxiom AXIOM_B = DATA.getOWLSubClassOfAxiom(CLASS_A, CLASS_C);

    private static final OWLAnnotation ANNOTATION =
            DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("note"));

    private static final OWLAnnotation OTHER_ANNOTATION =
            DATA.getOWLAnnotation(DATA.getRDFSLabel(), DATA.getOWLLiteral("label"));

    private static final IRI IMPORTED = IRI.create("http://example.org/imported");

    private static final IRI ONTOLOGY_IRI = IRI.create("http://example.org/ont");

    @Test
    void preserves_a_single_add() {
        AddAxiom add = add(AXIOM_A);

        assertThat(minimise(add)).containsExactly(add);
    }

    @Test
    void preserves_a_single_remove() {
        RemoveAxiom removal = remove(AXIOM_A);

        assertThat(minimise(removal)).containsExactly(removal);
    }

    @Test
    void preserves_a_single_non_axiom_change() {
        AddOntologyAnnotation annotation = annotation(ANNOTATION);

        assertThat(minimise(annotation)).containsExactly(annotation);
    }

    @Test
    void cancels_an_add_followed_by_a_remove() {
        assertThat(minimise(add(AXIOM_A), remove(AXIOM_A))).isEmpty();
    }

    @Test
    void keeps_the_first_add_when_a_later_add_remains() {
        AddAxiom first = add(AXIOM_A);
        AddAxiom later = add(AXIOM_A);

        List<OntologyChange> minimised = minimise(first, remove(AXIOM_A), later);

        assertThat(minimised).containsExactly(first);
        assertThat(minimised.getFirst()).isSameAs(first);
    }

    @Test
    void cancels_two_add_remove_pairs() {
        assertThat(minimise(add(AXIOM_A), remove(AXIOM_A), add(AXIOM_A), remove(AXIOM_A)))
                .isEmpty();
    }

    @Test
    void collapses_duplicate_adds_to_the_first() {
        AddAxiom first = add(AXIOM_A);
        AddAxiom duplicate = add(AXIOM_A);

        List<OntologyChange> minimised = minimise(first, duplicate);

        assertThat(minimised).containsExactly(first);
        assertThat(minimised.getFirst()).isSameAs(first);
    }

    @Test
    void cancels_a_remove_followed_by_an_add() {
        assertThat(minimise(remove(AXIOM_A), add(AXIOM_A))).isEmpty();
    }

    @Test
    void collapses_duplicate_removes_to_the_first() {
        RemoveAxiom first = remove(AXIOM_A);
        RemoveAxiom duplicate = remove(AXIOM_A);

        List<OntologyChange> minimised = minimise(first, duplicate);

        assertThat(minimised).containsExactly(first);
        assertThat(minimised.getFirst()).isSameAs(first);
    }

    @Test
    void keeps_the_first_remove_when_a_later_remove_remains() {
        RemoveAxiom first = remove(AXIOM_A);
        RemoveAxiom later = remove(AXIOM_A);

        List<OntologyChange> minimised = minimise(add(AXIOM_A), first, later);

        assertThat(minimised).containsExactly(first);
        assertThat(minimised.getFirst()).isSameAs(first);
    }

    @Test
    void duplicate_adds_then_duplicate_removes_leave_the_first_removal() {
        RemoveAxiom firstRemoval = remove(AXIOM_A);
        RemoveAxiom laterRemoval = remove(AXIOM_A);

        List<OntologyChange> minimised = minimise(add(AXIOM_A), add(AXIOM_A), firstRemoval, laterRemoval);

        assertThat(minimised).containsExactly(firstRemoval);
        assertThat(minimised.getFirst()).isSameAs(firstRemoval);
    }

    @Test
    void does_not_cancel_the_same_axiom_on_different_ontologies() {
        RemoveAxiom removal = remove(ONTOLOGY, AXIOM_A);
        AddAxiom add = add(OTHER_ONTOLOGY, AXIOM_A);
        AddAxiom otherAdd = add(OTHER_ONTOLOGY, AXIOM_A);
        RemoveAxiom otherRemoval = remove(OTHER_ONTOLOGY, AXIOM_A);

        assertThat(minimise(removal, add)).containsExactly(removal, add);
        assertThat(minimise(add, removal)).containsExactly(add, removal);
        assertThat(minimise(add(ONTOLOGY, AXIOM_A), otherAdd)).containsExactly(add(ONTOLOGY, AXIOM_A), otherAdd);
        assertThat(minimise(removal, otherRemoval)).containsExactly(removal, otherRemoval);
    }

    @Test
    void cancels_structurally_equal_axiom_objects() {
        assertThat(AXIOM_A_COPY).isEqualTo(AXIOM_A).isNotSameAs(AXIOM_A);

        assertThat(minimise(add(AXIOM_A), remove(AXIOM_A_COPY))).isEmpty();
    }

    @Test
    void does_not_cancel_different_axioms() {
        AddAxiom add = add(AXIOM_A);
        RemoveAxiom removal = remove(AXIOM_B);

        assertThat(minimise(add, removal)).containsExactly(add, removal);
    }

    @Test
    void non_axiom_changes_keep_their_original_position() {
        AddAxiom firstAdd = add(AXIOM_A);
        AddOntologyAnnotation annotation = annotation(ANNOTATION);
        AddImport addImport = new AddImport(ONTOLOGY, IMPORTED);
        DeclareOntology declared = new DeclareOntology(ONTOLOGY, Optional.of(ONTOLOGY_IRI), Optional.empty());
        AddAxiom laterAdd = add(AXIOM_A);

        List<OntologyChange> minimised = minimise(firstAdd, annotation, remove(AXIOM_A), addImport, laterAdd, declared);

        assertThat(minimised).containsExactly(firstAdd, annotation, addImport, declared);
        assertThat(minimised.getFirst()).isSameAs(firstAdd);
    }

    @Test
    void duplicate_annotations_imports_and_declarations_are_not_collapsed() {
        AddOntologyAnnotation annotation = annotation(ANNOTATION);
        RemoveOntologyAnnotation removeAnnotation = new RemoveOntologyAnnotation(ONTOLOGY, ANNOTATION);
        AddOntologyAnnotation sameAnnotationAgain = annotation(ANNOTATION);
        AddImport addImport = new AddImport(ONTOLOGY, IMPORTED);
        RemoveImport removeImport = new RemoveImport(ONTOLOGY, IMPORTED);
        DeclareOntology first = new DeclareOntology(ONTOLOGY, Optional.empty(), Optional.empty());
        DeclareOntology second = new DeclareOntology(ONTOLOGY, Optional.of(ONTOLOGY_IRI), Optional.empty());
        RemoveOntologyAnnotation otherAnnotation = new RemoveOntologyAnnotation(OTHER_ONTOLOGY, OTHER_ANNOTATION);

        List<OntologyChange> minimised = minimise(
                annotation,
                removeAnnotation,
                sameAnnotationAgain,
                addImport,
                removeImport,
                first,
                second,
                otherAnnotation);

        assertThat(minimised)
                .containsExactly(
                        annotation,
                        removeAnnotation,
                        sameAnnotationAgain,
                        addImport,
                        removeImport,
                        first,
                        second,
                        otherAnnotation);
    }

    @Test
    void empty_list_stays_empty() {
        assertThat(ChangeListMinimiser.minimise(List.of())).isEmpty();
    }

    @Test
    void rejects_a_null_list() {
        assertThatThrownBy(() -> ChangeListMinimiser.minimise(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("changes");
    }

    @Test
    void rejects_a_null_change() {
        List<OntologyChange> changes = new ArrayList<>();
        changes.add(add(AXIOM_A));
        changes.add(null);

        assertThatThrownBy(() -> ChangeListMinimiser.minimise(changes)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void does_not_mutate_the_input_and_returns_an_unmodifiable_list() {
        List<OntologyChange> input = new ArrayList<>();
        input.add(add(AXIOM_A));
        input.add(remove(AXIOM_A));
        List<OntologyChange> before = List.copyOf(input);

        List<OntologyChange> minimised = ChangeListMinimiser.minimise(input);

        assertThat(input).containsExactlyElementsOf(before);
        assertThat(minimised).isEmpty();
        assertThatThrownBy(() -> minimised.add(add(AXIOM_B))).isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<OntologyChange> minimise(OntologyChange... changes) {
        return ChangeListMinimiser.minimise(List.of(changes));
    }

    private static AddAxiom add(OWLAxiom axiom) {
        return add(ONTOLOGY, axiom);
    }

    private static AddAxiom add(OntologyKey ontology, OWLAxiom axiom) {
        return new AddAxiom(ontology, axiom);
    }

    private static RemoveAxiom remove(OWLAxiom axiom) {
        return remove(ONTOLOGY, axiom);
    }

    private static RemoveAxiom remove(OntologyKey ontology, OWLAxiom axiom) {
        return new RemoveAxiom(ontology, axiom);
    }

    private static AddOntologyAnnotation annotation(OWLAnnotation annotation) {
        return new AddOntologyAnnotation(ONTOLOGY, annotation);
    }
}
