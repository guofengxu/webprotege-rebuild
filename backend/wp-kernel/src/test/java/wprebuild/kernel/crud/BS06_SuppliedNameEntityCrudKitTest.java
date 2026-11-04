package wprebuild.kernel.crud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;

/** Supplied-name entity IRIs and their labels (BS-06). */
class BS06_SuppliedNameEntityCrudKitTest {

    private static final String PREFIX = "http://stuff/";

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private static final IRI OWL_THING = IRI.create("http://www.w3.org/2002/07/owl#Thing");

    private final SuppliedNameEntityCrudKit kit = new SuppliedNameEntityCrudKit();

    private final PrefixedNameExpander expander = new PrefixedNameExpander(Map.of());

    @Test
    void camel_case_is_the_default_whitespace_treatment() {
        assertThat(SuppliedNameKitSettings.defaults().whiteSpace()).isEqualTo(WhiteSpaceTreatment.CAMEL_CASE);
    }

    @Test
    void non_empty_name_adds_a_declaration_and_a_label_in_the_requested_language() {
        CreatedEntity created = kit.create(
                EntityType.CLASS,
                "MyLabel",
                Optional.of("en"),
                settings(WhiteSpaceTreatment.CAMEL_CASE, "fr"),
                expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "MyLabel"));
        assertThat(created.creationChanges()).hasSize(2);
        assertDeclaration(created);
        OWLLiteral label = labelLiteral(created);
        // BS-06: the supplied-name label is the IRI local name. With no whitespace it equals the input.
        assertThat(label.getLiteral()).isEqualTo("MyLabel");
        assertThat(label.getLang()).isEqualTo("en");
    }

    @Test
    void absent_language_uses_the_default_language() {
        CreatedEntity created = kit.create(
                EntityType.CLASS,
                "MyLabel",
                Optional.empty(),
                settings(WhiteSpaceTreatment.CAMEL_CASE, "de"),
                expander);

        assertThat(labelLiteral(created).getLang()).isEqualTo("de");
    }

    @Test
    void owl_thing_uses_the_built_in_iri_and_labels_the_local_name() {
        CreatedEntity created = kit.create(
                EntityType.CLASS,
                "owl:Thing",
                Optional.of("en"),
                settings(WhiteSpaceTreatment.CAMEL_CASE, "en"),
                expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(OWL_THING);
        assertThat(labelLiteral(created).getLiteral()).isEqualTo("Thing");
        assertThat(labelLiteral(created).getLang()).isEqualTo("en");
    }

    @Test
    void quoted_iri_is_used_directly_and_labelled_with_the_local_name() {
        CreatedEntity created = kit.create(
                EntityType.CLASS,
                "<http://x/y>",
                Optional.of("en"),
                settings(WhiteSpaceTreatment.CAMEL_CASE, "en"),
                expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create("http://x/y"));
        assertThat(labelLiteral(created).getLiteral()).isEqualTo("y");
    }

    @Test
    void camel_case_collapses_whitespace_in_the_iri() {
        CreatedEntity created = kit.create(
                EntityType.CLASS,
                "My Class",
                Optional.of("en"),
                settings(WhiteSpaceTreatment.CAMEL_CASE, "en"),
                expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "MyClass"));
        assertThat(labelLiteral(created).getLiteral()).isEqualTo("MyClass");
    }

    @Test
    void underscores_replace_whitespace_in_the_iri() {
        CreatedEntity created = kit.create(
                EntityType.CLASS,
                "My Class",
                Optional.of("en"),
                settings(WhiteSpaceTreatment.UNDERSCORES, "en"),
                expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "My_Class"));
        assertThat(labelLiteral(created).getLiteral()).isEqualTo("My_Class");
    }

    @Test
    void blank_name_does_not_add_a_label() {
        CreatedEntity created = kit.create(
                EntityType.CLASS, "", Optional.of("en"), settings(WhiteSpaceTreatment.CAMEL_CASE, "en"), expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create(PREFIX));
        assertThat(created.creationChanges()).hasSize(1);
        assertDeclaration(created);
    }

    @Test
    void project_prefix_overrides_a_built_in_prefix() {
        PrefixedNameExpander custom = new PrefixedNameExpander(Map.of("owl", "http://example.org/owl#"));
        CreatedEntity created = kit.create(
                EntityType.CLASS,
                "owl:Thing",
                Optional.of("en"),
                settings(WhiteSpaceTreatment.CAMEL_CASE, "en"),
                custom);

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create("http://example.org/owl#Thing"));
        assertThat(labelLiteral(created).getLiteral()).isEqualTo("Thing");
    }

    private static NewEntitySettings settings(WhiteSpaceTreatment whiteSpace, String defaultLang) {
        return new NewEntitySettings(
                PREFIX, new SuppliedNameKitSettings(whiteSpace), NewEntitySettings.RDFS_LABEL, defaultLang, ONTOLOGY);
    }

    private static void assertDeclaration(CreatedEntity created) {
        OntologyChange change = created.creationChanges().get(0);
        assertThat(change.ontology()).isEqualTo(ONTOLOGY);
        assertThat(change.axiom()).hasValueSatisfying(axiom -> {
            assertThat(axiom).isInstanceOf(OWLDeclarationAxiom.class);
            assertThat(((OWLDeclarationAxiom) axiom).getEntity()).isEqualTo(created.entity());
        });
    }

    private static OWLLiteral labelLiteral(CreatedEntity created) {
        return labelAxiom(created).getValue().asLiteral().orElseThrow();
    }

    private static OWLAnnotationAssertionAxiom labelAxiom(CreatedEntity created) {
        OntologyChange change = created.creationChanges().get(1);
        assertThat(change.ontology()).isEqualTo(ONTOLOGY);
        assertThat(change.axiom().orElseThrow()).isInstanceOf(OWLAnnotationAssertionAxiom.class);
        return (OWLAnnotationAssertionAxiom) change.axiom().orElseThrow();
    }
}
