package wprebuild.kernel.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;

/** BS-02. One case per specification row, plus registration and same-batch dependencies. */
class BS02_EffectiveChangesTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final OntologyKey MISSING = OntologyKey.of(9);

    private static final OWLClass CLASS_A = DATA.getOWLClass(IRI.create("http://example.org/A"));

    private static final OWLClass CLASS_B = DATA.getOWLClass(IRI.create("http://example.org/B"));

    private static final OWLClass CLASS_C = DATA.getOWLClass(IRI.create("http://example.org/C"));

    private static final OWLAxiom AXIOM_A = DATA.getOWLSubClassOfAxiom(CLASS_A, CLASS_B);

    private static final OWLAxiom AXIOM_B = DATA.getOWLSubClassOfAxiom(CLASS_A, CLASS_C);

    private static final OWLAnnotation COMMENT =
            DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("note"));

    private static final IRI IMPORTED = IRI.create("http://example.org/imported");

    @Test
    void adds_an_axiom_only_when_the_ontology_does_not_contain_it() {
        AddAxiom present = new AddAxiom(FIRST, AXIOM_A);
        AddAxiom missing = new AddAxiom(FIRST, AXIOM_B);
        ProjectOntologies current = project(List.of(declare(FIRST), present));

        assertThat(EffectiveChanges.compute(current, List.of(present, missing))).containsExactly(missing);
    }

    @Test
    void removes_an_axiom_only_when_the_ontology_contains_it() {
        RemoveAxiom missing = new RemoveAxiom(FIRST, AXIOM_B);
        RemoveAxiom present = new RemoveAxiom(FIRST, AXIOM_A);
        ProjectOntologies current = project(List.of(declare(FIRST), new AddAxiom(FIRST, AXIOM_A)));

        assertThat(EffectiveChanges.compute(current, List.of(missing, present))).containsExactly(present);
    }

    @Test
    void adds_an_ontology_annotation_only_when_the_ontology_does_not_contain_it() {
        AddOntologyAnnotation add = new AddOntologyAnnotation(FIRST, COMMENT);
        ProjectOntologies withAnnotation = project(List.of(declare(FIRST), add));
        assertThat(EffectiveChanges.compute(withAnnotation, List.of(add))).isEmpty();

        ProjectOntologies without = project(List.of(declare(FIRST)));
        AddOntologyAnnotation first = new AddOntologyAnnotation(FIRST, COMMENT);
        AddOntologyAnnotation duplicate = new AddOntologyAnnotation(FIRST, COMMENT);
        RemoveOntologyAnnotation removal = new RemoveOntologyAnnotation(FIRST, COMMENT);

        assertThat(EffectiveChanges.compute(without, List.of(first, duplicate, removal)))
                .containsExactly(first, removal);
    }

    @Test
    void removes_an_ontology_annotation_only_when_the_ontology_contains_it() {
        RemoveOntologyAnnotation removal = new RemoveOntologyAnnotation(FIRST, COMMENT);
        assertThat(EffectiveChanges.compute(project(List.of(declare(FIRST))), List.of(removal)))
                .isEmpty();

        ProjectOntologies withAnnotation = project(List.of(declare(FIRST), new AddOntologyAnnotation(FIRST, COMMENT)));
        AddOntologyAnnotation add = new AddOntologyAnnotation(FIRST, COMMENT);
        RemoveOntologyAnnotation again = new RemoveOntologyAnnotation(FIRST, COMMENT);

        assertThat(EffectiveChanges.compute(withAnnotation, List.of(removal, add, again)))
                .containsExactly(removal, add, again);
    }

    /**
     * BS-02：旧系统的 {@code ChangeFilter} 不处理导入变更，visitor 默认返回 {@code null}，导入变更永远无效。
     * 新系统按目标本体是否已包含该导入判断添加与删除。
     */
    @Test
    void adds_or_removes_an_import_only_when_that_changes_the_ontology() {
        ProjectOntologies without = project(List.of(declare(FIRST)));
        AddImport first = new AddImport(FIRST, IMPORTED);
        AddImport duplicate = new AddImport(FIRST, IMPORTED);
        RemoveImport removal = new RemoveImport(FIRST, IMPORTED);

        assertThat(EffectiveChanges.compute(without, List.of(new RemoveImport(FIRST, IMPORTED))))
                .isEmpty();
        assertThat(EffectiveChanges.compute(without, List.of(first, duplicate, removal)))
                .containsExactly(first, removal);

        ProjectOntologies withImport = project(List.of(declare(FIRST), new AddImport(FIRST, IMPORTED)));
        AddImport addPresent = new AddImport(FIRST, IMPORTED);
        RemoveImport removePresent = new RemoveImport(FIRST, IMPORTED);
        assertThat(EffectiveChanges.compute(withImport, List.of(addPresent, removePresent)))
                .containsExactly(removePresent);
    }

    @Test
    void treats_declare_ontology_as_effective_only_when_the_key_is_absent() {
        ProjectOntologies current = project(List.of(declare(FIRST)));
        DeclareOntology created = declare(SECOND);
        DeclareOntology duplicate =
                new DeclareOntology(SECOND, Optional.of(IRI.create("http://example.org/other")), Optional.empty());

        assertThat(EffectiveChanges.compute(current, List.of(created, duplicate)))
                .containsExactly(created);
        assertThat(EffectiveChanges.compute(current, List.of(declare(FIRST)))).isEmpty();
    }

    @Test
    void keeps_an_axiom_added_after_the_ontology_is_declared_in_the_same_batch() {
        ProjectOntologies current = project(List.of(declare(FIRST)));
        DeclareOntology created = declare(SECOND);
        AddAxiom add = new AddAxiom(SECOND, AXIOM_A);
        RemoveAxiom removeMissing = new RemoveAxiom(SECOND, AXIOM_B);

        assertThat(EffectiveChanges.compute(current, List.of(created, add, removeMissing)))
                .containsExactly(created, add);
        assertThat(current.keys()).containsExactly(FIRST);
        assertThat(current.info(SECOND)).isEmpty();
    }

    @Test
    void applies_annotation_and_import_changes_to_an_ontology_declared_in_the_same_batch() {
        ProjectOntologies current = project(List.of(declare(FIRST)));
        DeclareOntology created = declare(SECOND);
        AddOntologyAnnotation annotation = new AddOntologyAnnotation(SECOND, COMMENT);
        AddImport imported = new AddImport(SECOND, IMPORTED);
        RemoveOntologyAnnotation absent = new RemoveOntologyAnnotation(SECOND, COMMENT);

        assertThat(EffectiveChanges.compute(current, List.of(created, absent, annotation, imported)))
                .containsExactly(created, annotation, imported);
    }

    @Test
    void rejects_a_change_that_references_an_unregistered_ontology() {
        ProjectOntologies current = project(List.of(declare(FIRST)));

        assertThatThrownBy(() -> EffectiveChanges.compute(current, List.of(new AddAxiom(MISSING, AXIOM_A))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MISSING.value());
        assertThatThrownBy(() ->
                        EffectiveChanges.compute(current, List.of(new RemoveOntologyAnnotation(MISSING, COMMENT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MISSING.value());
        assertThatThrownBy(() -> EffectiveChanges.compute(current, List.of(new AddImport(MISSING, IMPORTED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MISSING.value());
        assertThatThrownBy(() ->
                        EffectiveChanges.compute(current, List.of(new AddAxiom(SECOND, AXIOM_A), declare(SECOND))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SECOND.value());
    }

    /**
     * BS-01 cancels one add and one remove of the same axiom regardless of order, including when that
     * cancellation does not match left-to-right set application against the current ontology.
     */
    @Test
    void minimises_before_filtering() {
        ProjectOntologies empty = project(List.of(declare(FIRST)));
        AddAxiom first = new AddAxiom(FIRST, AXIOM_A);
        RemoveAxiom removal = new RemoveAxiom(FIRST, AXIOM_A);
        AddAxiom third = new AddAxiom(FIRST, AXIOM_A);

        assertThat(EffectiveChanges.compute(empty, List.of())).isEmpty();
        assertThat(EffectiveChanges.compute(empty, List.of(first, removal))).isEmpty();
        assertThat(EffectiveChanges.compute(empty, List.of(removal, first))).isEmpty();
        assertThat(EffectiveChanges.compute(empty, List.of(first, removal, third)))
                .containsExactly(first);

        ProjectOntologies withAxiom = project(List.of(declare(FIRST), new AddAxiom(FIRST, AXIOM_A)));
        assertThat(EffectiveChanges.compute(withAxiom, List.of(first, removal))).isEmpty();
    }

    @Test
    void rejects_null_arguments() {
        ProjectOntologies current = project(List.of(declare(FIRST)));

        assertThatThrownBy(() -> EffectiveChanges.compute(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EffectiveChanges.compute(current, null)).isInstanceOf(NullPointerException.class);
    }

    private static DeclareOntology declare(OntologyKey key) {
        return new DeclareOntology(key, Optional.empty(), Optional.empty());
    }

    private static MutableProjectOntologies project(List<OntologyChange> changes) {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(changes);
        return ontologies;
    }
}
