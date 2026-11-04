package wprebuild.kernel.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;

/**
 * BS-08. Random add/remove sequences of subclass, equivalence, and declaration axioms stay equal
 * to a full rebuild.
 */
class BS08_ClassHierarchyIncrementalPropertyTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final List<OWLClass> CLASSES = List.of(cls("A"), cls("B"), cls("C"), cls("D"));

    private static final List<OntologyChange> EDITS = edits();

    @Property(tries = 200)
    void step_by_step_updates_match_rebuild(@ForAll("sequences") List<OntologyChange> changes) {
        MutableProjectOntologies ontologies = emptyProject();
        ClassHierarchyView incremental = new ClassHierarchyView();
        incremental.rebuild(ontologies);
        for (OntologyChange change : changes) {
            Set<HierarchyEdge<OWLClass>> before = edgesOf(incremental);
            List<OntologyChange> step = List.of(change);
            ontologies.apply(step);
            incremental.onApplied(step, ontologies);
            assertDeltaMatches(before, incremental);
        }
        assertMatchesRebuild(incremental, ontologies);
    }

    @Property(tries = 200)
    void one_batch_matches_rebuild(@ForAll("sequences") List<OntologyChange> changes) {
        MutableProjectOntologies ontologies = emptyProject();
        ClassHierarchyView incremental = new ClassHierarchyView();
        incremental.rebuild(ontologies);
        Set<HierarchyEdge<OWLClass>> before = edgesOf(incremental);
        ontologies.apply(changes);
        incremental.onApplied(changes, ontologies);
        assertDeltaMatches(before, incremental);
        assertMatchesRebuild(incremental, ontologies);
    }

    @Provide
    Arbitrary<List<OntologyChange>> sequences() {
        return Arbitraries.of(EDITS.toArray(OntologyChange[]::new)).list().ofMaxSize(24);
    }

    private static Set<HierarchyEdge<OWLClass>> edgesOf(ClassHierarchyView view) {
        Set<HierarchyEdge<OWLClass>> edges = new HashSet<>();
        List<OWLClass> classes = new ArrayList<>(CLASSES);
        classes.add(DATA.getOWLThing());
        for (OWLClass cls : classes) {
            for (OWLClass parent : view.parents(cls)) {
                edges.add(new HierarchyEdge<>(parent, cls));
            }
        }
        return edges;
    }

    private static void assertDeltaMatches(Set<HierarchyEdge<OWLClass>> before, ClassHierarchyView view) {
        Set<HierarchyEdge<OWLClass>> after = edgesOf(view);
        Set<HierarchyEdge<OWLClass>> added = new HashSet<>(after);
        added.removeAll(before);
        Set<HierarchyEdge<OWLClass>> removed = new HashSet<>(before);
        removed.removeAll(after);
        assertThat(view.lastDelta().added()).isEqualTo(added);
        assertThat(view.lastDelta().removed()).isEqualTo(removed);
    }

    private static void assertMatchesRebuild(ClassHierarchyView incremental, MutableProjectOntologies ontologies) {
        ClassHierarchyView rebuilt = new ClassHierarchyView();
        rebuilt.rebuild(ontologies);
        assertThat(incremental.roots()).isEqualTo(rebuilt.roots());
        for (OWLClass cls : observed(ontologies)) {
            assertThat(incremental.parents(cls)).isEqualTo(rebuilt.parents(cls));
            assertThat(incremental.children(cls)).isEqualTo(rebuilt.children(cls));
            assertThat(incremental.hasChildren(cls)).isEqualTo(rebuilt.hasChildren(cls));
            assertThat(incremental.pathsToRoot(cls)).containsExactlyInAnyOrderElementsOf(rebuilt.pathsToRoot(cls));
        }
        incremental.rebuild(ontologies);
        assertThat(incremental.lastDelta().added()).isEmpty();
        assertThat(incremental.lastDelta().removed()).isEmpty();
        for (OWLClass cls : observed(ontologies)) {
            assertThat(incremental.parents(cls)).isEqualTo(rebuilt.parents(cls));
        }
    }

    private static Set<OWLClass> observed(MutableProjectOntologies ontologies) {
        List<OWLClass> classes = new ArrayList<>(CLASSES);
        classes.add(DATA.getOWLThing());
        ontologies
                .signature()
                .filter(entity -> entity.isOWLClass())
                .map(entity -> entity.asOWLClass())
                .forEach(classes::add);
        return Set.copyOf(classes);
    }

    private static MutableProjectOntologies emptyProject() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(
                new DeclareOntology(FIRST, Optional.empty(), Optional.empty()),
                new DeclareOntology(SECOND, Optional.empty(), Optional.empty())));
        return ontologies;
    }

    private static List<OntologyChange> edits() {
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < CLASSES.size(); i++) {
            OWLClass left = CLASSES.get(i);
            axioms.add(DATA.getOWLDeclarationAxiom(left));
            for (OWLClass right : CLASSES) {
                axioms.add(DATA.getOWLSubClassOfAxiom(left, right));
            }
            OWLClass other = CLASSES.get((i + 1) % CLASSES.size());
            OWLClass third = CLASSES.get((i + 2) % CLASSES.size());
            axioms.add(DATA.getOWLEquivalentClassesAxiom(left, other));
            axioms.add(DATA.getOWLEquivalentClassesAxiom(left, DATA.getOWLObjectIntersectionOf(other, third)));
            axioms.add(DATA.getOWLEquivalentClassesAxiom(left, DATA.getOWLObjectIntersectionOf(left, other)));
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

    private static OWLClass cls(String local) {
        return DATA.getOWLClass(IRI.create("http://example.org/pool/" + local));
    }
}
