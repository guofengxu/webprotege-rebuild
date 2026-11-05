package wprebuild.kernel.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.testsupport.CountingProjectOntologies;

/**
 * BS-08. An incremental hierarchy update checks entity presence one entity at a time, so its cost
 * stays with the change and does not materialise the project signature (P1-24).
 */
class BS08_HierarchyViewIncrementalCostTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    @Test
    void counting_wrapper_records_signature_and_axioms_of_type_calls() {
        CountingProjectOntologies counted = new CountingProjectOntologies(declared());

        assertThat(counted.signature().count()).isZero();
        assertThat(counted.axiomsOfType(AxiomType.SUBCLASS_OF).count()).isZero();
        assertThat(counted.signatureCalls()).isEqualTo(1);
        assertThat(counted.axiomsOfTypeCalls()).isEqualTo(1);

        counted.reset();

        assertThat(counted.signatureCalls()).isZero();
        assertThat(counted.axiomsOfTypeCalls()).isZero();
    }

    @Test
    void class_hierarchy_incremental_update_does_not_materialise_the_signature() {
        OWLClass child = DATA.getOWLClass(iri("Child"));
        OWLClass parent = DATA.getOWLClass(iri("Parent"));
        check(
                ClassHierarchyView::new,
                DATA.getOWLSubClassOfAxiom(child, parent),
                Set.of(DATA.getOWLThing(), child, parent));
    }

    @Test
    void object_property_hierarchy_incremental_update_does_not_materialise_the_signature() {
        OWLObjectProperty child = DATA.getOWLObjectProperty(iri("Child"));
        OWLObjectProperty parent = DATA.getOWLObjectProperty(iri("Parent"));
        check(
                ObjectPropertyHierarchyView::new,
                DATA.getOWLSubObjectPropertyOfAxiom(child, parent),
                Set.of(DATA.getOWLTopObjectProperty(), child, parent));
    }

    @Test
    void data_property_hierarchy_incremental_update_does_not_materialise_the_signature() {
        OWLDataProperty child = DATA.getOWLDataProperty(iri("Child"));
        OWLDataProperty parent = DATA.getOWLDataProperty(iri("Parent"));
        check(
                DataPropertyHierarchyView::new,
                DATA.getOWLSubDataPropertyOfAxiom(child, parent),
                Set.of(DATA.getOWLTopDataProperty(), child, parent));
    }

    @Test
    void annotation_property_hierarchy_incremental_update_does_not_materialise_the_signature() {
        OWLAnnotationProperty child = DATA.getOWLAnnotationProperty(iri("Child"));
        OWLAnnotationProperty parent = DATA.getOWLAnnotationProperty(iri("Parent"));
        check(
                AnnotationPropertyHierarchyView::new,
                DATA.getOWLSubAnnotationPropertyOfAxiom(child, parent),
                Set.of(child, parent));
    }

    private static <E extends OWLEntity> void check(Supplier<HierarchyView<E>> views, OWLAxiom link, Set<E> compared) {
        onAppliedOnce(views, List.of(), new AddAxiom(ONTOLOGY, link), compared);
        onAppliedOnce(views, List.of(new AddAxiom(ONTOLOGY, link)), new RemoveAxiom(ONTOLOGY, link), compared);
    }

    private static <E extends OWLEntity> void onAppliedOnce(
            Supplier<HierarchyView<E>> views, List<OntologyChange> before, OntologyChange change, Set<E> compared) {
        MutableProjectOntologies ontologies = declared();
        if (!before.isEmpty()) {
            ontologies.apply(before);
        }
        HierarchyView<E> incremental = views.get();
        incremental.rebuild(ontologies);

        ontologies.apply(List.of(change));
        CountingProjectOntologies counted = new CountingProjectOntologies(ontologies);
        incremental.onApplied(List.of(change), counted);

        assertThat(counted.signatureCalls()).isZero();
        HierarchyView<E> rebuilt = views.get();
        rebuilt.rebuild(ontologies);
        assertThat(incremental.roots()).isEqualTo(rebuilt.roots());
        for (E entity : compared) {
            assertThat(incremental.children(entity)).isEqualTo(rebuilt.children(entity));
        }
    }

    private static MutableProjectOntologies declared() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(new DeclareOntology(ONTOLOGY, Optional.empty(), Optional.empty())));
        return ontologies;
    }

    private static IRI iri(String local) {
        return IRI.create("http://example.org/" + local);
    }
}
