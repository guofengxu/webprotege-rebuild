package wprebuild.kernel.crud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;

/** UUID entity IRIs and their labels (BS-06). */
class BS06_UuidEntityCrudKitTest {

    private static final String PREFIX = "http://stuff/";

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private static final IRI OWL_THING = IRI.create("http://www.w3.org/2002/07/owl#Thing");

    private static final IRI QUOTED = IRI.create("http://x/y");

    private final UuidEntityCrudKit kit = new UuidEntityCrudKit();

    private final PrefixedNameExpander expander = new PrefixedNameExpander(Map.of());

    @Test
    void defaults_are_uuid_and_rdfs_label() {
        NewEntitySettings settings = NewEntitySettings.defaults(PREFIX, ONTOLOGY);

        assertThat(settings.kit()).isEqualTo(new UuidKitSettings());
        assertThatObject(settings.labelProperty()).isEqualTo(NewEntitySettings.RDFS_LABEL);
        assertThat(settings.iriPrefix()).isEqualTo(PREFIX);
        assertThat(settings.targetOntology()).isEqualTo(ONTOLOGY);
        assertThat(settings.defaultLang()).isEmpty();
    }

    @Test
    void suffix_matches_r_followed_by_base62() {
        CreatedEntity first = kit.create(EntityType.CLASS, "Person", Optional.of("en"), settings("en"), expander);
        CreatedEntity second = kit.create(EntityType.CLASS, "Person", Optional.of("en"), settings("en"), expander);

        assertThat(suffix(first)).matches("R[0-9A-Za-z]+");
        assertThat(suffix(second)).matches("R[0-9A-Za-z]+");
        assertThat(suffix(first)).isNotEqualTo(suffix(second));
        assertThat(first.entity().getIRI().toString()).isEqualTo(PREFIX + suffix(first));
    }

    @Test
    void non_empty_name_adds_a_declaration_and_a_label_in_the_requested_language() {
        CreatedEntity created = kit.create(EntityType.CLASS, "MyLabel", Optional.of("en"), settings("fr"), expander);

        assertThat(created.creationChanges()).hasSize(2);
        assertDeclaration(created);
        OWLLiteral label = labelLiteral(created);
        assertThat(label.getLiteral()).isEqualTo("MyLabel");
        assertThat(label.getLang()).isEqualTo("en");
        assertThatObject(labelAxiom(created).getProperty().getIRI()).isEqualTo(NewEntitySettings.RDFS_LABEL);
    }

    @Test
    void absent_language_uses_the_default_language() {
        CreatedEntity created = kit.create(EntityType.CLASS, "MyLabel", Optional.empty(), settings("de"), expander);

        assertThat(labelLiteral(created).getLang()).isEqualTo("de");
    }

    @Test
    void label_uses_the_configured_annotation_property() {
        IRI property = IRI.create("http://www.w3.org/2004/02/skos/core#prefLabel");
        NewEntitySettings settings = new NewEntitySettings(PREFIX, new UuidKitSettings(), property, "en", ONTOLOGY);

        CreatedEntity created = kit.create(EntityType.CLASS, "Person", Optional.of("en"), settings, expander);

        assertThat(labelAxiom(created).getProperty()).isInstanceOf(OWLAnnotationProperty.class);
        assertThatObject(labelAxiom(created).getProperty().getIRI()).isEqualTo(property);
    }

    @Test
    void owl_thing_uses_the_built_in_iri_and_keeps_the_supplied_name_as_the_label() {
        CreatedEntity created = kit.create(EntityType.CLASS, "owl:Thing", Optional.of("en"), settings("en"), expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(OWL_THING);
        assertThat(labelLiteral(created).getLiteral()).isEqualTo("owl:Thing");
        assertThat(labelLiteral(created).getLang()).isEqualTo("en");
    }

    @Test
    void quoted_iri_is_used_directly_and_labelled_with_the_iri() {
        CreatedEntity created =
                kit.create(EntityType.CLASS, "<http://x/y>", Optional.of("en"), settings("en"), expander);

        assertThatObject(created.entity().getIRI()).isEqualTo(QUOTED);
        assertThat(labelLiteral(created).getLiteral()).isEqualTo("http://x/y");
        assertThat(labelLiteral(created).getLang()).isEqualTo("en");
    }

    @Test
    void blank_name_does_not_add_a_label() {
        CreatedEntity created = kit.create(EntityType.CLASS, "", Optional.of("en"), settings("en"), expander);

        assertThat(created.creationChanges()).hasSize(1);
        assertDeclaration(created);
        assertThat(suffix(created)).matches("R[0-9A-Za-z]+");
    }

    @Test
    void creates_the_requested_entity_type() {
        CreatedEntity created =
                kit.create(EntityType.OBJECT_PROPERTY, "partOf", Optional.empty(), settings(""), expander);

        assertThat(created.entity()).isInstanceOf(OWLObjectProperty.class);
        assertThat(labelLiteral(created).getLang()).isEmpty();
    }

    @Test
    void base62_encodes_the_unsigned_uuid() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID highBit = UUID.fromString("923e4567-e89b-12d3-a456-426614174000");

        assertThat(Base62.encode(uuid)).isEqualTo("iaTzi6e65lkVY6de1E3rK");
        assertThat(Base62.encode(highBit)).isEqualTo("Eb7U6MHhqvecaSf16ZrufO");
        assertThat(Base62.encode(uuid)).matches("[0-9A-Za-z]+");
    }

    @Test
    void expands_built_in_prefixes_and_ignores_plain_text() {
        Map<String, String> builtIn = Map.of(
                "owl:Thing", "http://www.w3.org/2002/07/owl#Thing",
                "rdf:type", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                "rdfs:label", "http://www.w3.org/2000/01/rdf-schema#label",
                "xsd:string", "http://www.w3.org/2001/XMLSchema#string",
                "skos:prefLabel", "http://www.w3.org/2004/02/skos/core#prefLabel",
                "dc:title", "http://purl.org/dc/elements/1.1/title",
                "dcterms:title", "http://purl.org/dc/terms/title");

        builtIn.forEach((name, iri) -> assertThat(expander.expand(name)).contains(IRI.create(iri)));
        assertThat(expander.expand("Person")).isEmpty();
        assertThat(expander.expand("owl:")).isEmpty();
        assertThat(expander.expand("http://example.org/Person")).isEmpty();
    }

    private NewEntitySettings settings(String defaultLang) {
        return new NewEntitySettings(
                PREFIX, new UuidKitSettings(), NewEntitySettings.RDFS_LABEL, defaultLang, ONTOLOGY);
    }

    private static String suffix(CreatedEntity created) {
        String iri = created.entity().getIRI().toString();
        assertThat(iri).startsWith(PREFIX);
        return iri.substring(PREFIX.length());
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
