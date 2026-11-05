package wprebuild.kernel.shortform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.vocab.DublinCoreVocabulary;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;

/**
 * BS-09. A project language list picks the first matching annotation; otherwise the local name, or
 * the full IRI when that name is empty. Built-in entities keep their prefixed names.
 */
class BS09_DisplayNameViewTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final IRI RDFS_LABEL = OWLRDFVocabulary.RDFS_LABEL.getIRI();

    private static final IRI SKOS_PREF_LABEL = SKOSVocabulary.PREFLABEL.getIRI();

    private static final IRI DC_TITLE = DublinCoreVocabulary.TITLE.getIRI();

    private static final IRI DCTERMS_TITLE = IRI.create("http://purl.org/dc/terms/title");

    private static final DisplayNameSetting LABEL_ZH = new DisplayNameSetting(RDFS_LABEL, "zh");

    private static final DisplayNameSetting LABEL_EN = new DisplayNameSetting(RDFS_LABEL, "en");

    private static final List<DisplayNameSetting> ZH_THEN_EN = List.of(LABEL_ZH, LABEL_EN);

    private static final OWLClass TRAY = cls("http://example.org/Tray");

    private static final OWLClass BIN = cls("http://example.org/Bin");

    @Test
    void chinese_label_is_preferred_over_english() {
        DisplayNameView view = loaded(ZH_THEN_EN, label(TRAY, "zh", "托盘"), label(TRAY, "en", "Tray"));

        assertThat(view.displayName(TRAY)).isEqualTo("托盘");
        assertThat(view.allNames(TRAY)).containsEntry(LABEL_ZH, "托盘").containsEntry(LABEL_EN, "Tray");
    }

    @Test
    void english_label_is_used_when_chinese_is_absent() {
        DisplayNameView view = loaded(ZH_THEN_EN, label(TRAY, "en", "Tray"));

        assertThat(view.displayName(TRAY)).isEqualTo("Tray");
        assertThat(view.allNames(TRAY)).containsOnlyKeys(LABEL_EN);
    }

    @Test
    void local_name_is_used_when_no_configured_label_matches() {
        DisplayNameView view = loaded(ZH_THEN_EN, label(TRAY, "", "untagged"));

        assertThat(view.displayName(TRAY)).isEqualTo("Tray");
        assertThat(view.allNames(TRAY)).isEmpty();
    }

    @Test
    void full_iri_is_used_when_the_local_name_is_empty() {
        OWLClass slash = cls("http://example.org/");
        OWLClass trailing = cls("http://example.org/path/elements/final/");
        DisplayNameView view = loaded(ZH_THEN_EN, declaration(slash), declaration(trailing));

        assertThat(view.displayName(slash)).isEqualTo("http://example.org/");
        assertThat(view.displayName(trailing)).isEqualTo("http://example.org/path/elements/final/");
    }

    @Test
    void local_name_keeps_the_path_segment_and_decodes_escapes() {
        DisplayNameView view = loaded(ZH_THEN_EN, new OntologyChange[0]);

        assertThat(view.displayName(cls("http://example.org/123abc"))).isEqualTo("123abc");
        assertThat(view.displayName(cls("http://example.org/ns#123"))).isEqualTo("123");
        assertThat(view.displayName(cls("http://example.org/2"))).isEqualTo("2");
        assertThat(view.displayName(cls("http://example.org/ns#"))).isEqualTo("ns#");
        assertThat(view.displayName(cls("http://example.org/path#gro%C3%9F"))).isEqualTo("groß");
        assertThat(view.displayName(cls("http://example.org/path#%E9%96%80%E7%94%9F")))
                .isEqualTo("門生");
        assertThat(view.displayName(cls("http://example.org/path#With%23Hash"))).isEqualTo("With#Hash");
        assertThat(view.displayName(cls("http://example.org/a%ZZ"))).isEqualTo("a%ZZ");
    }

    @Test
    void built_in_entity_uses_its_prefixed_name() {
        DisplayNameView view = loaded(ZH_THEN_EN, label(DATA.getOWLThing(), "zh", "万物"));

        assertThat(view.displayName(DATA.getOWLThing())).isEqualTo("owl:Thing");
        assertThat(view.displayName(DATA.getOWLAnnotationProperty(RDFS_LABEL))).isEqualTo("rdfs:label");
        assertThat(view.allNames(DATA.getOWLThing())).containsEntry(LABEL_ZH, "万物");
    }

    @Test
    void changed_label_is_reported_and_unchanged_entity_is_not() {
        Harness harness = new Harness(ZH_THEN_EN);
        OWLAnnotationAssertionAxiom original = label(TRAY, "zh", "托盘");
        harness.apply(declaration(TRAY), declaration(BIN), add(original), add(label(BIN, "zh", "料箱")));
        harness.view.rebuild(harness.ontologies);

        harness.apply(new RemoveAxiom(FIRST, original), add(label(TRAY, "zh", "周转托盘")));

        assertThat(harness.view.lastChanged()).containsExactly(TRAY);
        assertThat(harness.view.displayName(TRAY)).isEqualTo("周转托盘");
        assertThat(harness.view.displayName(BIN)).isEqualTo("料箱");
    }

    @Test
    void editing_a_later_language_does_not_change_the_display_name() {
        Harness harness = new Harness(ZH_THEN_EN);
        harness.apply(declaration(TRAY), add(label(TRAY, "zh", "托盘")), add(label(TRAY, "en", "Tray")));
        harness.view.rebuild(harness.ontologies);

        harness.apply(add(label(TRAY, "en", "Bin")));

        assertThat(harness.view.lastChanged()).isEmpty();
        assertThat(harness.view.displayName(TRAY)).isEqualTo("托盘");
        assertThat(harness.view.allNames(TRAY)).containsEntry(LABEL_EN, "Bin");
    }

    @Test
    void removing_an_entity_reports_the_display_name_change() {
        Harness harness = new Harness(ZH_THEN_EN);
        OWLAnnotationAssertionAxiom label = label(TRAY, "zh", "托盘");
        AddAxiom declared = declaration(TRAY);
        harness.apply(declared, add(label));
        harness.view.rebuild(harness.ontologies);

        harness.apply(new RemoveAxiom(FIRST, label), new RemoveAxiom(FIRST, declared.owlAxiom()));

        assertThat(harness.view.lastChanged()).contains(TRAY);
        assertThat(harness.view.displayName(TRAY)).isEqualTo("Tray");
    }

    @Test
    void empty_settings_use_the_default_property_order() {
        OWLClass named = cls("http://example.org/Named");
        DisplayNameView view = loaded(
                List.of(),
                label(named, SKOS_PREF_LABEL, "", "preferred"),
                label(named, RDFS_LABEL, "", "labelled"),
                label(named, DC_TITLE, "", "dc"),
                label(named, DCTERMS_TITLE, "", "dcterms"));

        assertThat(view.displayName(named)).isEqualTo("preferred");
    }

    @Test
    void empty_settings_fall_through_the_default_properties() {
        OWLClass labelled = cls("http://example.org/Labelled");
        OWLClass titled = cls("http://example.org/Titled");
        OWLClass terms = cls("http://example.org/Terms");
        DisplayNameView view = loaded(
                List.of(),
                label(labelled, RDFS_LABEL, "", "labelled"),
                label(titled, DC_TITLE, "", "dc"),
                label(terms, DCTERMS_TITLE, "", "dcterms"));

        assertThat(view.displayName(labelled)).isEqualTo("labelled");
        assertThat(view.displayName(titled)).isEqualTo("dc");
        assertThat(view.displayName(terms)).isEqualTo("dcterms");
    }

    @Test
    void empty_language_matches_only_an_untagged_literal() {
        DisplayNameView view = loaded(List.of(new DisplayNameSetting(RDFS_LABEL, "")), label(TRAY, "en", "Tray"));

        assertThat(view.displayName(TRAY)).isEqualTo("Tray");
        assertThat(view.allNames(TRAY)).isEmpty();
    }

    @Test
    void language_tag_match_ignores_case() {
        DisplayNameView view = loaded(List.of(new DisplayNameSetting(RDFS_LABEL, "ZH")), label(TRAY, "zh", "托盘"));

        assertThat(view.displayName(TRAY)).isEqualTo("托盘");
        assertThat(new DisplayNameSetting(RDFS_LABEL, "ZH")).isEqualTo(LABEL_ZH);
    }

    @Test
    void duplicate_literals_use_the_lowest_lexical_form() {
        DisplayNameView view = loaded(ZH_THEN_EN, label(TRAY, "zh", "乙"), label(TRAY, "zh", "甲"));

        // U+4E59 (乙) sorts before U+7532 (甲).
        assertThat(view.displayName(TRAY)).isEqualTo("乙");
    }

    @Test
    void rebuild_clears_last_changed_and_matches_incremental_updates() {
        Harness harness = new Harness(ZH_THEN_EN);
        harness.apply(declaration(TRAY), declaration(BIN), add(label(TRAY, "zh", "托盘")), add(label(BIN, "en", "Bin")));

        DisplayNameView rebuilt = new DisplayNameView(ZH_THEN_EN);
        rebuilt.rebuild(harness.ontologies);

        assertThat(harness.view.lastChanged()).contains(TRAY);
        harness.view.rebuild(harness.ontologies);
        assertThat(harness.view.lastChanged()).isEmpty();
        assertThat(harness.view.displayName(TRAY)).isEqualTo("托盘");
        assertThat(rebuilt.lastChanged()).isEmpty();
        for (OWLEntity entity : List.of(TRAY, BIN, DATA.getOWLThing())) {
            assertThat(harness.view.displayName(entity)).isEqualTo(rebuilt.displayName(entity));
            assertThat(harness.view.allNames(entity)).isEqualTo(rebuilt.allNames(entity));
        }
    }

    @Test
    void declaration_without_a_label_does_not_change_the_display_name() {
        Harness harness = new Harness(ZH_THEN_EN);

        harness.apply(declaration(TRAY));

        assertThat(harness.view.lastChanged()).isEmpty();
        assertThat(harness.view.displayName(TRAY)).isEqualTo("Tray");

        harness.apply(add(label(TRAY, "zh", "托盘")));

        assertThat(harness.view.lastChanged()).containsExactly(TRAY);
        assertThat(harness.view.displayName(TRAY)).isEqualTo("托盘");
    }

    @Test
    void queries_fail_before_a_snapshot() {
        DisplayNameView view = new DisplayNameView(ZH_THEN_EN);

        assertThatThrownBy(() -> view.displayName(TRAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rebuilt");
        assertThatThrownBy(() -> view.allNames(TRAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rebuilt");
        assertThatThrownBy(view::lastChanged)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rebuilt");
    }

    @Test
    void null_arguments_are_rejected() {
        DisplayNameView view = new DisplayNameView(ZH_THEN_EN);

        assertThatThrownBy(() -> new DisplayNameSetting(null, "zh"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("annotationProperty");
        assertThatThrownBy(() -> new DisplayNameSetting(RDFS_LABEL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lang");
        assertThatThrownBy(() -> new DisplayNameView(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settings");
        assertThatThrownBy(() -> view.displayName(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entity");
        assertThatThrownBy(() -> view.allNames(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entity");
        assertThatThrownBy(() -> view.rebuild(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ontologies");
        assertThatThrownBy(() -> view.onApplied(null, OwlApiProjectOntologies.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effectiveChanges");
        assertThatThrownBy(() -> view.onApplied(List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ontologies");
    }

    private static DisplayNameView loaded(List<DisplayNameSetting> settings, OWLAnnotationAssertionAxiom... labels) {
        return loaded(settings, axioms(labels));
    }

    private static DisplayNameView loaded(List<DisplayNameSetting> settings, OntologyChange... changes) {
        Harness harness = new Harness(settings);
        harness.apply(changes);
        harness.view.rebuild(harness.ontologies);
        return harness.view;
    }

    private static OntologyChange[] axioms(OWLAnnotationAssertionAxiom... labels) {
        OntologyChange[] changes = new OntologyChange[labels.length];
        for (int i = 0; i < labels.length; i++) {
            changes[i] = new AddAxiom(FIRST, labels[i]);
        }
        return changes;
    }

    private static AddAxiom declaration(OWLClass cls) {
        return new AddAxiom(FIRST, DATA.getOWLDeclarationAxiom(cls));
    }

    private static AddAxiom add(OWLAnnotationAssertionAxiom axiom) {
        return new AddAxiom(FIRST, axiom);
    }

    private static OWLAnnotationAssertionAxiom label(OWLEntity entity, String lang, String text) {
        return label(entity, RDFS_LABEL, lang, text);
    }

    private static OWLAnnotationAssertionAxiom label(OWLEntity entity, IRI property, String lang, String text) {
        OWLAnnotationProperty annotationProperty = DATA.getOWLAnnotationProperty(property);
        return DATA.getOWLAnnotationAssertionAxiom(
                annotationProperty,
                entity.getIRI(),
                lang.isEmpty() ? DATA.getOWLLiteral(text) : DATA.getOWLLiteral(text, lang));
    }

    private static OWLClass cls(String iri) {
        return DATA.getOWLClass(IRI.create(iri));
    }

    private static final class Harness {

        private final MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();

        private final DisplayNameView view;

        private Harness(List<DisplayNameSetting> settings) {
            view = new DisplayNameView(settings);
            ontologies.apply(List.of(new DeclareOntology(FIRST, Optional.empty(), Optional.empty())));
            view.rebuild(ontologies);
        }

        private void apply(OntologyChange... changes) {
            List<OntologyChange> batch = List.of(changes);
            ontologies.apply(batch);
            view.onApplied(batch, ontologies);
        }
    }
}
