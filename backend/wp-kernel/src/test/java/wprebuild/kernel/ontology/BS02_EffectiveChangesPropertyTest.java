package wprebuild.kernel.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.ChangeListMinimiser;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;

/**
 * BS-02 on a pool of 3 axioms and 2 ontologies.
 *
 * <p>The oracle minimises first (BS-01), then applies only the changes that alter set membership.
 * That is not the same as applying the original sequence from left to right.
 */
class BS02_EffectiveChangesPropertyTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final List<OntologyKey> KEYS = List.of(FIRST, SECOND);

    private static final List<OWLAxiom> AXIOMS = axioms();

    private static final List<AddAxiom> INITIAL_ADDS = initialAdds();

    private static final List<OntologyChange> EDITS = edits();

    @Property(tries = 200)
    void effective_changes_match_minimised_set_application_and_inverses_restore_the_prior_state(
            @ForAll("scenarios") Scenario scenario) {
        MutableProjectOntologies source = load(scenario.initialAdds());
        AxiomState before = stateOf(source);

        List<OntologyChange> effective = EffectiveChanges.compute(source, scenario.changes());
        assertThat(stateOf(source)).isEqualTo(before);

        MutableProjectOntologies applied = load(scenario.initialAdds());
        applied.apply(effective);
        assertThat(stateOf(applied)).isEqualTo(applyMinimised(before, scenario.changes()));

        List<OntologyChange> inverses = new ArrayList<>();
        for (int i = effective.size() - 1; i >= 0; i--) {
            inverses.add(effective.get(i).inverse());
        }
        applied.apply(inverses);
        assertThat(stateOf(applied)).isEqualTo(before);
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<Boolean>> presence = Arbitraries.of(false, true).list().ofSize(INITIAL_ADDS.size());
        Arbitrary<List<OntologyChange>> changes =
                Arbitraries.of(EDITS.toArray(OntologyChange[]::new)).list().ofMaxSize(16);
        return Combinators.combine(presence, changes).as((flags, edits) -> {
            List<OntologyChange> initial = new ArrayList<>();
            for (int i = 0; i < flags.size(); i++) {
                if (flags.get(i)) {
                    initial.add(INITIAL_ADDS.get(i));
                }
            }
            return new Scenario(initial, edits);
        });
    }

    private static AxiomState applyMinimised(AxiomState initial, List<OntologyChange> changes) {
        Map<OntologyKey, Set<OWLAxiom>> next = new HashMap<>();
        initial.axioms().forEach((key, axioms) -> next.put(key, new HashSet<>(axioms)));
        for (OntologyChange change : ChangeListMinimiser.minimise(changes)) {
            switch (change) {
                case AddAxiom add -> next.get(add.ontology()).add(add.owlAxiom());
                case RemoveAxiom remove -> next.get(remove.ontology()).remove(remove.owlAxiom());
                default -> throw new AssertionError(change);
            }
        }
        return new AxiomState(next);
    }

    private static MutableProjectOntologies load(List<OntologyChange> initialAdds) {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        List<OntologyChange> seed = new ArrayList<>();
        seed.add(new DeclareOntology(FIRST, Optional.empty(), Optional.empty()));
        seed.add(new DeclareOntology(SECOND, Optional.empty(), Optional.empty()));
        seed.addAll(initialAdds);
        ontologies.apply(seed);
        return ontologies;
    }

    private static AxiomState stateOf(ProjectOntologies ontologies) {
        Map<OntologyKey, Set<OWLAxiom>> axioms = new HashMap<>();
        for (OntologyKey key : KEYS) {
            axioms.put(key, new HashSet<>(ontologies.axioms(key).toList()));
        }
        return new AxiomState(axioms);
    }

    private static List<OWLAxiom> axioms() {
        OWLClass a = DATA.getOWLClass(IRI.create("http://example.org/pool/A"));
        OWLClass b = DATA.getOWLClass(IRI.create("http://example.org/pool/B"));
        OWLClass c = DATA.getOWLClass(IRI.create("http://example.org/pool/C"));
        OWLClass d = DATA.getOWLClass(IRI.create("http://example.org/pool/D"));
        return List.of(
                DATA.getOWLSubClassOfAxiom(a, b), DATA.getOWLSubClassOfAxiom(a, c), DATA.getOWLSubClassOfAxiom(b, d));
    }

    private static List<AddAxiom> initialAdds() {
        List<AddAxiom> adds = new ArrayList<>();
        for (OntologyKey key : KEYS) {
            for (OWLAxiom axiom : AXIOMS) {
                adds.add(new AddAxiom(key, axiom));
            }
        }
        return List.copyOf(adds);
    }

    private static List<OntologyChange> edits() {
        List<OntologyChange> edits = new ArrayList<>();
        for (AddAxiom add : initialAdds()) {
            edits.add(add);
            edits.add(new RemoveAxiom(add.ontology(), add.owlAxiom()));
        }
        return List.copyOf(edits);
    }

    private record Scenario(List<OntologyChange> initialAdds, List<OntologyChange> changes) {}

    private record AxiomState(Map<OntologyKey, Set<OWLAxiom>> axioms) {}
}
