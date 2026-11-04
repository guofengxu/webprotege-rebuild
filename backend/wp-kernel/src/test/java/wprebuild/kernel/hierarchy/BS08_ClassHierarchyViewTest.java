package wprebuild.kernel.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;

/**
 * BS-08. Ports {@code ClassHierarchyProviderImpl_TestCase} onto {@link ClassHierarchyView} and locks
 * the edges that specification adds: multiple paths, cycles, and {@code lastDelta}.
 */
class BS08_ClassHierarchyViewTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final OWLClass THING = DATA.getOWLThing();

    private static final OWLClass A = cls("A");

    private static final OWLClass A2 = cls("A2");

    private static final OWLClass B = cls("B");

    private static final OWLClass C = cls("C");

    private static final OWLClass D = cls("D");

    private static final OWLClass E = cls("E");

    private static final OWLAxiom A_SUB_B = DATA.getOWLSubClassOfAxiom(A, B);

    private static final OWLAxiom B_SUB_C = DATA.getOWLSubClassOfAxiom(B, C);

    private static final OWLAxiom A2_EQUIVALENT_D_AND_E = DATA.getOWLEquivalentClassesAxiom(A2, intersection(D, E));

    @Test
    void asserted_superclass_is_a_parent() {
        ClassHierarchyView view = loaded(A_SUB_B, B_SUB_C, A2_EQUIVALENT_D_AND_E);

        assertThat(view.parents(A)).containsExactly(B);
    }

    @Test
    void equivalent_intersection_conjuncts_are_parents() {
        ClassHierarchyView view = loaded(A_SUB_B, B_SUB_C, A2_EQUIVALENT_D_AND_E);

        assertThat(view.parents(A2)).containsExactlyInAnyOrder(D, E);
    }

    @Test
    void path_to_root_includes_asserted_ancestors() {
        ClassHierarchyView view = loaded(A_SUB_B, B_SUB_C, A2_EQUIVALENT_D_AND_E);

        assertThat(view.pathsToRoot(A)).containsExactly(List.of(THING, C, B, A));
    }

    @Test
    void class_without_a_named_parent_hangs_under_thing() {
        ClassHierarchyView view = loaded(A_SUB_B, B_SUB_C, A2_EQUIVALENT_D_AND_E);

        assertThat(view.parents(E)).containsExactly(THING);
        assertThat(view.roots()).containsExactly(THING);
        assertThat(view.parents(THING)).isEmpty();
        assertThat(view.pathsToRoot(THING)).containsExactly(List.of(THING));
    }

    @Test
    void asserted_subclass_is_a_child() {
        ClassHierarchyView view = loaded(A_SUB_B, B_SUB_C, A2_EQUIVALENT_D_AND_E);

        assertThat(view.children(C)).containsExactly(B);
        assertThat(view.children(B)).containsExactly(A);
        assertThat(view.hasChildren(C)).isTrue();
        assertThat(view.hasChildren(A)).isFalse();
    }

    @Test
    void equivalent_intersection_conjunct_is_a_child() {
        ClassHierarchyView view = loaded(A_SUB_B, B_SUB_C, A2_EQUIVALENT_D_AND_E);

        assertThat(view.children(D)).containsExactly(A2);
        assertThat(view.children(E)).containsExactly(A2);
        assertThat(view.children(THING)).containsExactlyInAnyOrder(C, D, E);
    }

    @Test
    void multiple_parents_produce_one_path_per_branch() {
        ClassHierarchyView view = loaded(
                DATA.getOWLSubClassOfAxiom(B, D),
                DATA.getOWLSubClassOfAxiom(C, D),
                DATA.getOWLSubClassOfAxiom(A, B),
                DATA.getOWLSubClassOfAxiom(A, C));

        assertThat(view.pathsToRoot(A)).containsExactlyInAnyOrder(List.of(THING, D, B, A), List.of(THING, D, C, A));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void cycle_does_not_loop_and_has_no_path_to_thing() {
        ClassHierarchyView view = loaded(DATA.getOWLSubClassOfAxiom(A, B), DATA.getOWLSubClassOfAxiom(B, A));

        assertThat(view.parents(A)).containsExactly(B);
        assertThat(view.parents(B)).containsExactly(A);
        assertThat(view.children(THING)).isEmpty();
        assertThat(view.pathsToRoot(A)).isEmpty();
        assertThat(view.pathsToRoot(B)).isEmpty();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void cycle_keeps_the_simple_path_that_escapes_to_thing() {
        ClassHierarchyView view = loaded(
                DATA.getOWLSubClassOfAxiom(A, B), DATA.getOWLSubClassOfAxiom(B, A), DATA.getOWLSubClassOfAxiom(A, C));

        assertThat(view.pathsToRoot(A)).containsExactly(List.of(THING, C, A));
        assertThat(view.pathsToRoot(B)).containsExactly(List.of(THING, C, A, B));
    }

    @Test
    void subclass_of_an_intersection_does_not_add_parent_edges() {
        ClassHierarchyView view = loaded(DATA.getOWLSubClassOfAxiom(A, intersection(B, C)));

        assertThat(view.parents(A)).containsExactly(THING);
        assertThat(view.children(B)).isEmpty();
        assertThat(view.children(C)).isEmpty();
    }

    @Test
    void general_concept_inclusion_does_not_add_a_parent_edge() {
        ClassHierarchyView view = loaded(DATA.getOWLSubClassOfAxiom(intersection(A, B), C));

        assertThat(view.parents(A)).containsExactly(THING);
        assertThat(view.children(C)).isEmpty();
    }

    @Test
    void equivalent_named_classes_are_not_parents_of_each_other() {
        ClassHierarchyView view = loaded(DATA.getOWLEquivalentClassesAxiom(A, B));

        assertThat(view.parents(A)).containsExactly(THING);
        assertThat(view.parents(B)).containsExactly(THING);
        assertThat(view.children(A)).isEmpty();
        assertThat(view.children(B)).isEmpty();
    }

    @Test
    void nested_intersection_contributes_only_its_direct_named_operands() {
        OWLClassExpression nested = DATA.getOWLObjectIntersectionOf(intersection(B, C), D);
        ClassHierarchyView view = loaded(DATA.getOWLEquivalentClassesAxiom(A, nested));

        assertThat(view.parents(A)).containsExactly(D);
        assertThat(view.parents(B)).containsExactly(THING);
        assertThat(view.children(B)).isEmpty();
    }

    @Test
    void thing_stays_the_root_when_it_is_asserted_as_a_subclass() {
        ClassHierarchyView view = loaded(DATA.getOWLSubClassOfAxiom(THING, A));

        assertThat(view.parents(THING)).isEmpty();
        assertThat(view.children(A)).isEmpty();
        assertThat(view.parents(A)).containsExactly(THING);
        assertThat(view.roots()).containsExactly(THING);
    }

    @Test
    void explicit_thing_parent_is_kept_beside_another_named_parent() {
        ClassHierarchyView view = loaded(DATA.getOWLSubClassOfAxiom(A, THING), DATA.getOWLSubClassOfAxiom(A, B));

        assertThat(view.parents(A)).containsExactlyInAnyOrder(THING, B);
        assertThat(view.children(THING)).containsExactlyInAnyOrder(A, B);
        assertThat(view.children(B)).containsExactly(A);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void self_parent_is_a_cycle_and_is_not_placed_under_thing() {
        ClassHierarchyView view = loaded(DATA.getOWLSubClassOfAxiom(A, A));

        assertThat(view.parents(A)).containsExactly(A);
        assertThat(view.children(A)).containsExactly(A);
        assertThat(view.children(THING)).isEmpty();
        assertThat(view.pathsToRoot(A)).isEmpty();
    }

    @Test
    void axioms_in_any_project_ontology_contribute_edges() {
        Harness harness = new Harness();
        harness.apply(new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(A)));
        harness.apply(new AddAxiom(FIRST, A_SUB_B));
        harness.apply(new AddAxiom(SECOND, B_SUB_C));

        assertThat(harness.view.pathsToRoot(A)).containsExactly(List.of(THING, C, B, A));

        harness.apply(new AddAxiom(SECOND, A_SUB_B));
        harness.apply(new RemoveAxiom(FIRST, A_SUB_B));
        assertThat(harness.view.parents(A)).containsExactly(B);

        harness.apply(new RemoveAxiom(SECOND, A_SUB_B));
        assertThat(harness.view.parents(A)).containsExactly(THING);
    }

    @Test
    void unknown_class_has_no_edges() {
        ClassHierarchyView view = loaded(A_SUB_B);
        OWLClass missing = cls("missing");

        assertThat(view.parents(missing)).isEmpty();
        assertThat(view.children(missing)).isEmpty();
        assertThat(view.hasChildren(missing)).isFalse();
        assertThat(view.pathsToRoot(missing)).isEmpty();
    }

    @Test
    void queries_return_unmodifiable_copies() {
        ClassHierarchyView view = loaded(A_SUB_B);

        assertThatThrownBy(() -> view.parents(A).add(C)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.children(B).add(C)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.roots().add(A)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.pathsToRoot(A).get(0).add(C)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.lastDelta().added().add(new HierarchyEdge<>(B, A)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.lastDelta().removed().add(new HierarchyEdge<>(B, A)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void delta_rejects_an_edge_listed_as_both_added_and_removed() {
        HierarchyEdge<OWLClass> edge = new HierarchyEdge<>(B, A);

        assertThatThrownBy(() -> new HierarchyDelta<>(Set.of(edge), Set.of(edge)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both added and removed");
    }

    @Test
    void rebuild_reports_an_empty_delta() {
        ClassHierarchyView view = loaded(A_SUB_B, B_SUB_C, A2_EQUIVALENT_D_AND_E);

        assertThat(view.lastDelta().added()).isEmpty();
        assertThat(view.lastDelta().removed()).isEmpty();
    }

    @Test
    void last_delta_records_only_the_latest_applied_batch() {
        Harness harness = new Harness();
        harness.apply(new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(A)));

        harness.apply(new AddAxiom(FIRST, A_SUB_B));
        assertThat(harness.view.lastDelta().added())
                .containsExactlyInAnyOrder(new HierarchyEdge<>(THING, B), new HierarchyEdge<>(B, A));
        assertThat(harness.view.lastDelta().removed()).containsExactly(new HierarchyEdge<>(THING, A));

        harness.apply(new AddAxiom(FIRST, B_SUB_C));
        assertThat(harness.view.lastDelta().added())
                .containsExactlyInAnyOrder(new HierarchyEdge<>(THING, C), new HierarchyEdge<>(C, B));
        assertThat(harness.view.lastDelta().removed()).containsExactly(new HierarchyEdge<>(THING, B));

        harness.apply(new RemoveAxiom(FIRST, A_SUB_B));
        assertThat(harness.view.lastDelta().added()).containsExactly(new HierarchyEdge<>(THING, A));
        assertThat(harness.view.lastDelta().removed()).containsExactly(new HierarchyEdge<>(B, A));
        assertThat(harness.view.parents(B)).containsExactly(C);

        harness.view.rebuild(harness.ontologies);
        assertThat(harness.view.lastDelta().added()).isEmpty();
        assertThat(harness.view.lastDelta().removed()).isEmpty();
        assertThat(harness.view.pathsToRoot(B)).containsExactly(List.of(THING, C, B));
        assertThat(harness.view.parents(A)).containsExactly(THING);
    }

    @Test
    void removing_an_equivalent_intersection_drops_its_edges() {
        OWLAxiom declarationD = DATA.getOWLDeclarationAxiom(D);
        Harness harness = new Harness();
        harness.apply(new AddAxiom(FIRST, declarationD), new AddAxiom(FIRST, A2_EQUIVALENT_D_AND_E));

        assertThat(harness.view.parents(A2)).containsExactlyInAnyOrder(D, E);

        harness.apply(new RemoveAxiom(FIRST, A2_EQUIVALENT_D_AND_E));
        assertThat(harness.view.lastDelta().removed())
                .containsExactlyInAnyOrder(
                        new HierarchyEdge<>(D, A2), new HierarchyEdge<>(E, A2), new HierarchyEdge<>(THING, E));
        assertThat(harness.view.lastDelta().added()).isEmpty();
        assertThat(harness.view.parents(D)).containsExactly(THING);
        assertThat(harness.view.parents(A2)).isEmpty();
        assertThat(harness.view.children(D)).isEmpty();
    }

    @Test
    void annotation_change_does_not_move_edges() {
        Harness harness = new Harness();
        harness.apply(new AddAxiom(FIRST, A_SUB_B));
        OWLAnnotation comment = DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("note"));

        harness.apply(new AddOntologyAnnotation(FIRST, comment));

        assertThat(harness.view.lastDelta().added()).isEmpty();
        assertThat(harness.view.lastDelta().removed()).isEmpty();
        assertThat(harness.view.parents(A)).containsExactly(B);
    }

    @Test
    void one_batch_matches_a_fresh_rebuild() {
        List<OntologyChange> batch = List.of(
                new AddAxiom(FIRST, A_SUB_B),
                new AddAxiom(FIRST, B_SUB_C),
                new AddAxiom(SECOND, A2_EQUIVALENT_D_AND_E),
                new RemoveAxiom(FIRST, A_SUB_B));
        Harness harness = new Harness();
        harness.apply(batch.toArray(OntologyChange[]::new));

        ClassHierarchyView rebuilt = new ClassHierarchyView();
        rebuilt.rebuild(harness.ontologies);

        for (OWLClass cls : List.of(THING, A, A2, B, C, D, E)) {
            assertThat(harness.view.parents(cls)).isEqualTo(rebuilt.parents(cls));
            assertThat(harness.view.children(cls)).isEqualTo(rebuilt.children(cls));
            assertThat(harness.view.pathsToRoot(cls)).containsExactlyInAnyOrderElementsOf(rebuilt.pathsToRoot(cls));
        }
    }

    private static ClassHierarchyView loaded(OWLAxiom... axioms) {
        Harness harness = new Harness();
        OntologyChange[] changes = new OntologyChange[axioms.length];
        for (int i = 0; i < axioms.length; i++) {
            changes[i] = new AddAxiom(FIRST, axioms[i]);
        }
        harness.apply(changes);
        harness.view.rebuild(harness.ontologies);
        return harness.view;
    }

    private static OWLClass cls(String local) {
        return DATA.getOWLClass(IRI.create("http://example.org/" + local));
    }

    private static OWLClassExpression intersection(OWLClass... conjuncts) {
        return DATA.getOWLObjectIntersectionOf(conjuncts);
    }

    private static final class Harness {

        private final MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();

        private final ClassHierarchyView view = new ClassHierarchyView();

        private Harness() {
            ontologies.apply(List.of(
                    new DeclareOntology(FIRST, Optional.empty(), Optional.empty()),
                    new DeclareOntology(SECOND, Optional.empty(), Optional.empty())));
            view.rebuild(ontologies);
            assertThat(view.lastDelta()).isEqualTo(new HierarchyDelta<OWLClass>(Set.of(), Set.of()));
        }

        private void apply(OntologyChange... changes) {
            List<OntologyChange> batch = List.of(changes);
            ontologies.apply(batch);
            view.onApplied(batch, ontologies);
        }
    }
}
