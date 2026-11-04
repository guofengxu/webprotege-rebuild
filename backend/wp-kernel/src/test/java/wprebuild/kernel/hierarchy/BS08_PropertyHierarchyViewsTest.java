package wprebuild.kernel.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;

/**
 * BS-08. Object and data properties hang under their top property; annotation properties are a
 * forest of parentless roots. Incremental updates match a full rebuild.
 */
class BS08_PropertyHierarchyViewsTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final OWLObjectProperty TOP_OBJECT = DATA.getOWLTopObjectProperty();

    private static final OWLDataProperty TOP_DATA = DATA.getOWLTopDataProperty();

    private static final OWLObjectProperty OA = objectProperty("A");

    private static final OWLObjectProperty OB = objectProperty("B");

    private static final OWLObjectProperty OC = objectProperty("C");

    private static final OWLObjectProperty OD = objectProperty("D");

    private static final OWLDataProperty DA = dataProperty("A");

    private static final OWLDataProperty DB = dataProperty("B");

    private static final OWLDataProperty DC = dataProperty("C");

    private static final OWLDataProperty DD = dataProperty("D");

    private static final OWLAnnotationProperty AA = annotationProperty("A");

    private static final OWLAnnotationProperty AB = annotationProperty("B");

    private static final OWLAnnotationProperty AC = annotationProperty("C");

    private static final OWLAnnotationProperty AD = annotationProperty("D");

    private static final List<OWLObjectProperty> OBJECTS = List.of(OA, OB, OC, OD);

    private static final List<OWLDataProperty> DATA_PROPERTIES = List.of(DA, DB, DC, DD);

    private static final List<OWLAnnotationProperty> ANNOTATIONS = List.of(AA, AB, AC, AD);

    private static final List<OntologyChange> EDITS = edits();

    @Test
    void empty_project_exposes_only_the_builtin_property_roots() {
        Project project = new Project();

        assertThat(project.objects.roots()).containsExactly(TOP_OBJECT);
        assertThat(project.objects.parents(TOP_OBJECT)).isEmpty();
        assertThat(project.objects.children(TOP_OBJECT)).isEmpty();
        assertThat(project.objects.pathsToRoot(TOP_OBJECT)).containsExactly(List.of(TOP_OBJECT));
        assertThat(project.data.roots()).containsExactly(TOP_DATA);
        assertThat(project.data.pathsToRoot(TOP_DATA)).containsExactly(List.of(TOP_DATA));
        assertThat(project.annotations.roots()).isEmpty();
    }

    @Test
    void asserted_object_superproperty_is_the_parent() {
        Project project =
                loaded(DATA.getOWLSubObjectPropertyOfAxiom(OA, OB), DATA.getOWLSubObjectPropertyOfAxiom(OB, OC));

        assertThat(project.objects.parents(OA)).containsExactly(OB);
        assertThat(project.objects.children(OC)).containsExactly(OB);
        assertThat(project.objects.children(OB)).containsExactly(OA);
        assertThat(project.objects.hasChildren(OC)).isTrue();
        assertThat(project.objects.hasChildren(OA)).isFalse();
        assertThat(project.objects.pathsToRoot(OA)).containsExactly(List.of(TOP_OBJECT, OC, OB, OA));
        assertThat(project.objects.roots()).containsExactly(TOP_OBJECT);
    }

    @Test
    void object_property_without_a_named_parent_hangs_under_top() {
        Project project = loaded(DATA.getOWLDeclarationAxiom(OA), DATA.getOWLSubObjectPropertyOfAxiom(OB, OC));

        assertThat(project.objects.parents(OA)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.parents(OC)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.children(TOP_OBJECT)).containsExactlyInAnyOrder(OA, OC);
        assertThat(project.objects.pathsToRoot(OA)).containsExactly(List.of(TOP_OBJECT, OA));
    }

    @Test
    void multiple_object_parents_produce_one_path_per_branch() {
        Project project = loaded(
                DATA.getOWLSubObjectPropertyOfAxiom(OB, OD),
                DATA.getOWLSubObjectPropertyOfAxiom(OC, OD),
                DATA.getOWLSubObjectPropertyOfAxiom(OA, OB),
                DATA.getOWLSubObjectPropertyOfAxiom(OA, OC));

        assertThat(project.objects.pathsToRoot(OA))
                .containsExactlyInAnyOrder(List.of(TOP_OBJECT, OD, OB, OA), List.of(TOP_OBJECT, OD, OC, OA));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void object_cycle_is_not_placed_under_top() {
        Project project =
                loaded(DATA.getOWLSubObjectPropertyOfAxiom(OA, OB), DATA.getOWLSubObjectPropertyOfAxiom(OB, OA));

        assertThat(project.objects.parents(OA)).containsExactly(OB);
        assertThat(project.objects.parents(OB)).containsExactly(OA);
        assertThat(project.objects.children(TOP_OBJECT)).isEmpty();
        assertThat(project.objects.pathsToRoot(OA)).isEmpty();
        assertThat(project.objects.pathsToRoot(OB)).isEmpty();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void object_cycle_keeps_the_path_that_escapes_to_top() {
        Project project = loaded(
                DATA.getOWLSubObjectPropertyOfAxiom(OA, OB),
                DATA.getOWLSubObjectPropertyOfAxiom(OB, OA),
                DATA.getOWLSubObjectPropertyOfAxiom(OA, OC));

        assertThat(project.objects.pathsToRoot(OA)).containsExactly(List.of(TOP_OBJECT, OC, OA));
        assertThat(project.objects.pathsToRoot(OB)).containsExactly(List.of(TOP_OBJECT, OC, OA, OB));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void object_self_parent_is_a_cycle() {
        Project project = loaded(DATA.getOWLSubObjectPropertyOfAxiom(OA, OA));

        assertThat(project.objects.parents(OA)).containsExactly(OA);
        assertThat(project.objects.children(OA)).containsExactly(OA);
        assertThat(project.objects.children(TOP_OBJECT)).isEmpty();
        assertThat(project.objects.pathsToRoot(OA)).isEmpty();
    }

    @Test
    void inverse_and_property_chain_do_not_add_named_parent_edges() {
        Project project = loaded(
                DATA.getOWLSubObjectPropertyOfAxiom(OA, DATA.getOWLObjectInverseOf(OB)),
                DATA.getOWLSubObjectPropertyOfAxiom(DATA.getOWLObjectInverseOf(OC), OD),
                DATA.getOWLSubPropertyChainOfAxiom(List.of(OA, OB), OC));

        assertThat(project.objects.parents(OA)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.parents(OB)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.parents(OC)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.parents(OD)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.children(OC)).isEmpty();
        assertThat(project.objects.children(OD)).isEmpty();
    }

    @Test
    void equivalent_object_properties_are_not_parents_of_each_other() {
        Project project = loaded(DATA.getOWLEquivalentObjectPropertiesAxiom(OA, OB));

        assertThat(project.objects.parents(OA)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.parents(OB)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.children(OA)).isEmpty();
        assertThat(project.objects.children(OB)).isEmpty();
    }

    @Test
    void top_object_property_stays_the_root_when_asserted_as_a_subproperty() {
        Project project = loaded(DATA.getOWLSubObjectPropertyOfAxiom(TOP_OBJECT, OA));

        assertThat(project.objects.parents(TOP_OBJECT)).isEmpty();
        assertThat(project.objects.children(OA)).isEmpty();
        assertThat(project.objects.parents(OA)).containsExactly(TOP_OBJECT);
        assertThat(project.objects.roots()).containsExactly(TOP_OBJECT);
    }

    @Test
    void explicit_top_object_parent_is_kept_beside_another_named_parent() {
        Project project = loaded(
                DATA.getOWLSubObjectPropertyOfAxiom(OA, TOP_OBJECT), DATA.getOWLSubObjectPropertyOfAxiom(OA, OB));

        assertThat(project.objects.parents(OA)).containsExactlyInAnyOrder(TOP_OBJECT, OB);
        assertThat(project.objects.children(TOP_OBJECT)).containsExactlyInAnyOrder(OA, OB);
        assertThat(project.objects.children(OB)).containsExactly(OA);
    }

    @Test
    void object_axioms_in_any_project_ontology_contribute_edges() {
        Project project = new Project();
        project.apply(new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(OA)));
        project.apply(new AddAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));
        project.apply(new AddAxiom(SECOND, DATA.getOWLSubObjectPropertyOfAxiom(OB, OC)));

        assertThat(project.objects.pathsToRoot(OA)).containsExactly(List.of(TOP_OBJECT, OC, OB, OA));

        project.apply(new AddAxiom(SECOND, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));
        project.apply(new RemoveAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));
        assertThat(project.objects.parents(OA)).containsExactly(OB);

        project.apply(new RemoveAxiom(SECOND, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));
        assertThat(project.objects.parents(OA)).containsExactly(TOP_OBJECT);
    }

    @Test
    void object_last_delta_records_only_the_latest_batch() {
        Project project = new Project();
        project.apply(new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(OA)));
        assertThat(project.objects.lastDelta().added()).containsExactly(new HierarchyEdge<>(TOP_OBJECT, OA));
        assertThat(project.objects.lastDelta().removed()).isEmpty();

        project.apply(new AddAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));
        assertThat(project.objects.lastDelta().added())
                .containsExactlyInAnyOrder(new HierarchyEdge<>(TOP_OBJECT, OB), new HierarchyEdge<>(OB, OA));
        assertThat(project.objects.lastDelta().removed()).containsExactly(new HierarchyEdge<>(TOP_OBJECT, OA));

        project.apply(new AddAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OB, OC)));
        assertThat(project.objects.lastDelta().added())
                .containsExactlyInAnyOrder(new HierarchyEdge<>(TOP_OBJECT, OC), new HierarchyEdge<>(OC, OB));
        assertThat(project.objects.lastDelta().removed()).containsExactly(new HierarchyEdge<>(TOP_OBJECT, OB));

        project.apply(new RemoveAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));
        assertThat(project.objects.lastDelta().added()).containsExactly(new HierarchyEdge<>(TOP_OBJECT, OA));
        assertThat(project.objects.lastDelta().removed()).containsExactly(new HierarchyEdge<>(OB, OA));
        assertThat(project.objects.parents(OB)).containsExactly(OC);

        project.objects.rebuild(project.ontologies);
        assertThat(project.objects.lastDelta().added()).isEmpty();
        assertThat(project.objects.lastDelta().removed()).isEmpty();
        assertThat(project.objects.pathsToRoot(OB)).containsExactly(List.of(TOP_OBJECT, OC, OB));
        assertThat(project.objects.parents(OA)).containsExactly(TOP_OBJECT);
    }

    @Test
    void unknown_object_property_has_no_edges() {
        Project project = loaded(DATA.getOWLSubObjectPropertyOfAxiom(OA, OB));
        OWLObjectProperty missing = objectProperty("missing");

        assertThat(project.objects.parents(missing)).isEmpty();
        assertThat(project.objects.children(missing)).isEmpty();
        assertThat(project.objects.hasChildren(missing)).isFalse();
        assertThat(project.objects.pathsToRoot(missing)).isEmpty();
    }

    @Test
    void object_queries_return_unmodifiable_copies() {
        Project project = loaded(DATA.getOWLSubObjectPropertyOfAxiom(OA, OB));

        assertThatThrownBy(() -> project.objects.parents(OA).add(OC)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> project.objects.children(OB).add(OC))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> project.objects.roots().add(OA)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> project.objects.pathsToRoot(OA).get(0).add(OC))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void asserted_data_superproperty_is_the_parent() {
        Project project = loaded(DATA.getOWLSubDataPropertyOfAxiom(DA, DB), DATA.getOWLSubDataPropertyOfAxiom(DB, DC));

        assertThat(project.data.roots()).containsExactly(TOP_DATA);
        assertThat(project.data.parents(DA)).containsExactly(DB);
        assertThat(project.data.children(DC)).containsExactly(DB);
        assertThat(project.data.pathsToRoot(DA)).containsExactly(List.of(TOP_DATA, DC, DB, DA));
    }

    @Test
    void data_property_without_a_named_parent_hangs_under_top() {
        Project project = loaded(DATA.getOWLDeclarationAxiom(DA));

        assertThat(project.data.parents(DA)).containsExactly(TOP_DATA);
        assertThat(project.data.children(TOP_DATA)).containsExactly(DA);
        assertThat(project.data.pathsToRoot(DA)).containsExactly(List.of(TOP_DATA, DA));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void data_cycle_is_not_placed_under_top() {
        Project project = loaded(DATA.getOWLSubDataPropertyOfAxiom(DA, DB), DATA.getOWLSubDataPropertyOfAxiom(DB, DA));

        assertThat(project.data.children(TOP_DATA)).isEmpty();
        assertThat(project.data.pathsToRoot(DA)).isEmpty();
        assertThat(project.data.parents(DA)).containsExactly(DB);
    }

    @Test
    void equivalent_data_properties_are_not_parents_of_each_other() {
        Project project = loaded(DATA.getOWLEquivalentDataPropertiesAxiom(DA, DB));

        assertThat(project.data.parents(DA)).containsExactly(TOP_DATA);
        assertThat(project.data.parents(DB)).containsExactly(TOP_DATA);
    }

    @Test
    void top_data_property_stays_the_root_when_asserted_as_a_subproperty() {
        Project project = loaded(DATA.getOWLSubDataPropertyOfAxiom(TOP_DATA, DA));

        assertThat(project.data.parents(TOP_DATA)).isEmpty();
        assertThat(project.data.children(DA)).isEmpty();
        assertThat(project.data.parents(DA)).containsExactly(TOP_DATA);
        assertThat(project.data.roots()).containsExactly(TOP_DATA);
    }

    @Test
    void annotation_roots_are_properties_with_no_parent() {
        Project project = loaded(
                DATA.getOWLDeclarationAxiom(AD),
                DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AB),
                DATA.getOWLSubAnnotationPropertyOfAxiom(AB, AC));

        assertThat(project.annotations.roots()).containsExactlyInAnyOrder(AC, AD);
        assertThat(project.annotations.parents(AA)).containsExactly(AB);
        assertThat(project.annotations.children(AC)).containsExactly(AB);
        assertThat(project.annotations.children(AB)).containsExactly(AA);
        assertThat(project.annotations.hasChildren(AD)).isFalse();
        assertThat(project.annotations.pathsToRoot(AA)).containsExactly(List.of(AC, AB, AA));
        assertThat(project.annotations.pathsToRoot(AD)).containsExactly(List.of(AD));
        assertThat(project.annotations.parents(AC)).isEmpty();
    }

    @Test
    void builtin_annotation_properties_are_absent_until_they_appear() {
        Project project = new Project();

        assertThat(project.annotations.roots()).doesNotContain(DATA.getRDFSLabel());
        assertThat(project.annotations.pathsToRoot(DATA.getRDFSLabel())).isEmpty();

        project.apply(new AddOntologyAnnotation(
                FIRST, DATA.getOWLAnnotation(DATA.getRDFSLabel(), DATA.getOWLLiteral("title"))));

        assertThat(project.annotations.roots()).containsExactly(DATA.getRDFSLabel());
        assertThat(project.annotations.parents(DATA.getRDFSLabel())).isEmpty();
        assertThat(project.annotations.pathsToRoot(DATA.getRDFSLabel())).containsExactly(List.of(DATA.getRDFSLabel()));
        assertThat(project.objects.lastDelta().added()).isEmpty();
        assertThat(project.data.lastDelta().added()).isEmpty();
    }

    @Test
    void annotation_multiple_parents_produce_one_path_per_branch() {
        Project project = loaded(
                DATA.getOWLSubAnnotationPropertyOfAxiom(AB, AD),
                DATA.getOWLSubAnnotationPropertyOfAxiom(AC, AD),
                DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AB),
                DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AC));

        assertThat(project.annotations.roots()).containsExactly(AD);
        assertThat(project.annotations.pathsToRoot(AA))
                .containsExactlyInAnyOrder(List.of(AD, AB, AA), List.of(AD, AC, AA));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void annotation_cycle_is_not_a_root_and_does_not_loop() {
        Project project = loaded(
                DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AB), DATA.getOWLSubAnnotationPropertyOfAxiom(AB, AA));

        assertThat(project.annotations.roots()).isEmpty();
        assertThat(project.annotations.parents(AA)).containsExactly(AB);
        assertThat(project.annotations.pathsToRoot(AA)).isEmpty();
        assertThat(project.annotations.pathsToRoot(AB)).isEmpty();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void annotation_cycle_keeps_the_path_that_reaches_a_root() {
        Project project = loaded(
                DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AB),
                DATA.getOWLSubAnnotationPropertyOfAxiom(AB, AA),
                DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AC));

        assertThat(project.annotations.roots()).containsExactly(AC);
        assertThat(project.annotations.pathsToRoot(AA)).containsExactly(List.of(AC, AA));
        assertThat(project.annotations.pathsToRoot(AB)).containsExactly(List.of(AC, AA, AB));
    }

    @Test
    void annotation_last_delta_records_only_added_and_removed_edges() {
        Project project = new Project();
        project.apply(
                new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(AA)),
                new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(AB)));

        assertThat(project.annotations.roots()).containsExactlyInAnyOrder(AA, AB);
        assertThat(project.annotations.lastDelta().added()).isEmpty();
        assertThat(project.annotations.lastDelta().removed()).isEmpty();

        project.apply(new AddAxiom(FIRST, DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AB)));
        assertThat(project.annotations.lastDelta().added()).containsExactly(new HierarchyEdge<>(AB, AA));
        assertThat(project.annotations.lastDelta().removed()).isEmpty();
        assertThat(project.annotations.roots()).containsExactly(AB);

        project.apply(new RemoveAxiom(FIRST, DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AB)));
        assertThat(project.annotations.lastDelta().removed()).containsExactly(new HierarchyEdge<>(AB, AA));
        assertThat(project.annotations.lastDelta().added()).isEmpty();
        assertThat(project.annotations.roots()).containsExactlyInAnyOrder(AA, AB);
    }

    @Test
    void object_axiom_does_not_move_data_or_annotation_edges() {
        Project project = new Project();
        project.apply(new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(DA)));
        project.apply(new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(AA)));
        project.apply(new AddAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));

        assertThat(project.data.lastDelta().added()).isEmpty();
        assertThat(project.data.lastDelta().removed()).isEmpty();
        assertThat(project.data.parents(DA)).containsExactly(TOP_DATA);
        assertThat(project.annotations.lastDelta().added()).isEmpty();
        assertThat(project.annotations.roots()).containsExactly(AA);
    }

    @Test
    void one_batch_matches_a_fresh_rebuild() {
        List<OntologyChange> batch = List.of(
                new AddAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)),
                new AddAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OB, OC)),
                new AddAxiom(SECOND, DATA.getOWLSubDataPropertyOfAxiom(DA, DB)),
                new AddAxiom(FIRST, DATA.getOWLSubAnnotationPropertyOfAxiom(AA, AB)),
                new RemoveAxiom(FIRST, DATA.getOWLSubObjectPropertyOfAxiom(OA, OB)));
        Project project = new Project();
        project.apply(batch.toArray(OntologyChange[]::new));

        assertMatchesRebuild(project);
    }

    @Property(tries = 200)
    void step_by_step_updates_match_rebuild(@ForAll("sequences") List<OntologyChange> changes) {
        Project project = new Project();
        for (OntologyChange change : changes) {
            Set<HierarchyEdge<OWLObjectProperty>> objectsBefore = edgesOf(project.objects, objectNodes(project));
            Set<HierarchyEdge<OWLDataProperty>> dataBefore = edgesOf(project.data, dataNodes(project));
            Set<HierarchyEdge<OWLAnnotationProperty>> annotationsBefore =
                    edgesOf(project.annotations, annotationNodes(project));
            project.apply(change);
            assertDeltaMatches(objectsBefore, project.objects, objectNodes(project));
            assertDeltaMatches(dataBefore, project.data, dataNodes(project));
            assertDeltaMatches(annotationsBefore, project.annotations, annotationNodes(project));
        }
        assertMatchesRebuild(project);
    }

    @Property(tries = 200)
    void one_batch_of_random_edits_matches_rebuild(@ForAll("sequences") List<OntologyChange> changes) {
        Project project = new Project();
        Set<HierarchyEdge<OWLObjectProperty>> objectsBefore = edgesOf(project.objects, objectNodes(project));
        Set<HierarchyEdge<OWLDataProperty>> dataBefore = edgesOf(project.data, dataNodes(project));
        Set<HierarchyEdge<OWLAnnotationProperty>> annotationsBefore =
                edgesOf(project.annotations, annotationNodes(project));
        project.apply(changes.toArray(OntologyChange[]::new));
        assertDeltaMatches(objectsBefore, project.objects, objectNodes(project));
        assertDeltaMatches(dataBefore, project.data, dataNodes(project));
        assertDeltaMatches(annotationsBefore, project.annotations, annotationNodes(project));
        assertMatchesRebuild(project);
    }

    @Provide
    Arbitrary<List<OntologyChange>> sequences() {
        return Arbitraries.of(EDITS.toArray(OntologyChange[]::new)).list().ofMaxSize(24);
    }

    private static void assertMatchesRebuild(Project project) {
        ObjectPropertyHierarchyView objects = new ObjectPropertyHierarchyView();
        DataPropertyHierarchyView data = new DataPropertyHierarchyView();
        AnnotationPropertyHierarchyView annotations = new AnnotationPropertyHierarchyView();
        objects.rebuild(project.ontologies);
        data.rebuild(project.ontologies);
        annotations.rebuild(project.ontologies);
        assertView(project.objects, objects, objectNodes(project));
        assertView(project.data, data, dataNodes(project));
        assertView(project.annotations, annotations, annotationNodes(project));

        project.objects.rebuild(project.ontologies);
        project.data.rebuild(project.ontologies);
        project.annotations.rebuild(project.ontologies);
        assertThat(project.objects.lastDelta().added()).isEmpty();
        assertThat(project.objects.lastDelta().removed()).isEmpty();
        assertThat(project.data.lastDelta().added()).isEmpty();
        assertThat(project.annotations.lastDelta().added()).isEmpty();
        assertView(project.objects, objects, objectNodes(project));
        assertView(project.data, data, dataNodes(project));
        assertView(project.annotations, annotations, annotationNodes(project));
    }

    private static <E extends OWLEntity> void assertView(
            HierarchyView<E> incremental, HierarchyView<E> rebuilt, Set<E> nodes) {
        assertThat(incremental.roots()).isEqualTo(rebuilt.roots());
        for (E node : nodes) {
            assertThat(incremental.parents(node)).isEqualTo(rebuilt.parents(node));
            assertThat(incremental.children(node)).isEqualTo(rebuilt.children(node));
            assertThat(incremental.hasChildren(node)).isEqualTo(rebuilt.hasChildren(node));
            assertThat(incremental.pathsToRoot(node)).containsExactlyInAnyOrderElementsOf(rebuilt.pathsToRoot(node));
        }
    }

    private static <E extends OWLEntity> void assertDeltaMatches(
            Set<HierarchyEdge<E>> before, HierarchyView<E> view, Set<E> nodes) {
        Set<HierarchyEdge<E>> after = edgesOf(view, nodes);
        Set<HierarchyEdge<E>> added = new HashSet<>(after);
        added.removeAll(before);
        Set<HierarchyEdge<E>> removed = new HashSet<>(before);
        removed.removeAll(after);
        assertThat(view.lastDelta().added()).isEqualTo(added);
        assertThat(view.lastDelta().removed()).isEqualTo(removed);
    }

    private static <E extends OWLEntity> Set<HierarchyEdge<E>> edgesOf(HierarchyView<E> view, Set<E> nodes) {
        Set<HierarchyEdge<E>> edges = new HashSet<>();
        for (E node : nodes) {
            for (E parent : view.parents(node)) {
                edges.add(new HierarchyEdge<>(parent, node));
            }
        }
        return edges;
    }

    private static Set<OWLObjectProperty> objectNodes(Project project) {
        Set<OWLObjectProperty> nodes = new HashSet<>(OBJECTS);
        nodes.add(TOP_OBJECT);
        project.ontologies
                .signature()
                .filter(OWLEntity::isOWLObjectProperty)
                .map(OWLEntity::asOWLObjectProperty)
                .forEach(nodes::add);
        return nodes;
    }

    private static Set<OWLDataProperty> dataNodes(Project project) {
        Set<OWLDataProperty> nodes = new HashSet<>(DATA_PROPERTIES);
        nodes.add(TOP_DATA);
        project.ontologies
                .signature()
                .filter(OWLEntity::isOWLDataProperty)
                .map(OWLEntity::asOWLDataProperty)
                .forEach(nodes::add);
        return nodes;
    }

    private static Set<OWLAnnotationProperty> annotationNodes(Project project) {
        Set<OWLAnnotationProperty> nodes = new HashSet<>(ANNOTATIONS);
        nodes.add(DATA.getRDFSLabel());
        nodes.add(DATA.getRDFSComment());
        project.ontologies
                .signature()
                .filter(OWLEntity::isOWLAnnotationProperty)
                .map(OWLEntity::asOWLAnnotationProperty)
                .forEach(nodes::add);
        return nodes;
    }

    private static Project loaded(OWLAxiom... axioms) {
        Project project = new Project();
        OntologyChange[] changes = new OntologyChange[axioms.length];
        for (int i = 0; i < axioms.length; i++) {
            changes[i] = new AddAxiom(FIRST, axioms[i]);
        }
        project.apply(changes);
        project.rebuild();
        return project;
    }

    private static List<OntologyChange> edits() {
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < OBJECTS.size(); i++) {
            OWLObjectProperty left = OBJECTS.get(i);
            OWLDataProperty dataLeft = DATA_PROPERTIES.get(i);
            OWLAnnotationProperty annotationLeft = ANNOTATIONS.get(i);
            axioms.add(DATA.getOWLDeclarationAxiom(left));
            axioms.add(DATA.getOWLDeclarationAxiom(dataLeft));
            axioms.add(DATA.getOWLDeclarationAxiom(annotationLeft));
            for (int j = 0; j < OBJECTS.size(); j++) {
                axioms.add(DATA.getOWLSubObjectPropertyOfAxiom(left, OBJECTS.get(j)));
                axioms.add(DATA.getOWLSubDataPropertyOfAxiom(dataLeft, DATA_PROPERTIES.get(j)));
                axioms.add(DATA.getOWLSubAnnotationPropertyOfAxiom(annotationLeft, ANNOTATIONS.get(j)));
            }
            OWLObjectProperty other = OBJECTS.get((i + 1) % OBJECTS.size());
            axioms.add(DATA.getOWLSubObjectPropertyOfAxiom(left, DATA.getOWLObjectInverseOf(other)));
            axioms.add(DATA.getOWLEquivalentObjectPropertiesAxiom(left, other));
            axioms.add(DATA.getOWLSubPropertyChainOfAxiom(List.of(left, other), OBJECTS.get((i + 2) % OBJECTS.size())));
            axioms.add(DATA.getOWLEquivalentDataPropertiesAxiom(
                    dataLeft, DATA_PROPERTIES.get((i + 1) % DATA_PROPERTIES.size())));
            axioms.add(DATA.getOWLSubObjectPropertyOfAxiom(left, TOP_OBJECT));
            axioms.add(DATA.getOWLSubDataPropertyOfAxiom(dataLeft, TOP_DATA));
        }
        List<OntologyChange> changes = new ArrayList<>();
        for (OntologyKey key : List.of(FIRST, SECOND)) {
            for (OWLAxiom axiom : axioms) {
                changes.add(new AddAxiom(key, axiom));
                changes.add(new RemoveAxiom(key, axiom));
            }
        }
        return List.copyOf(changes);
    }

    private static OWLObjectProperty objectProperty(String local) {
        return DATA.getOWLObjectProperty(IRI.create("http://example.org/op/" + local));
    }

    private static OWLDataProperty dataProperty(String local) {
        return DATA.getOWLDataProperty(IRI.create("http://example.org/dp/" + local));
    }

    private static OWLAnnotationProperty annotationProperty(String local) {
        return DATA.getOWLAnnotationProperty(IRI.create("http://example.org/ap/" + local));
    }

    private static final class Project {

        private final MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();

        private final ObjectPropertyHierarchyView objects = new ObjectPropertyHierarchyView();

        private final DataPropertyHierarchyView data = new DataPropertyHierarchyView();

        private final AnnotationPropertyHierarchyView annotations = new AnnotationPropertyHierarchyView();

        private Project() {
            ontologies.apply(List.of(
                    new DeclareOntology(FIRST, Optional.empty(), Optional.empty()),
                    new DeclareOntology(SECOND, Optional.empty(), Optional.empty())));
            rebuild();
        }

        private void rebuild() {
            objects.rebuild(ontologies);
            data.rebuild(ontologies);
            annotations.rebuild(ontologies);
        }

        private void apply(OntologyChange... changes) {
            List<OntologyChange> batch = List.of(changes);
            ontologies.apply(batch);
            objects.onApplied(batch, ontologies);
            data.onApplied(batch, ontologies);
            annotations.onApplied(batch, ontologies);
        }
    }
}
