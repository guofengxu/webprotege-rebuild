package wprebuild.kernel.crud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;
import wprebuild.kernel.iri.FreshEntityIri;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;

/** {@link KitEntityCreator} chooses the kit named by the current settings (BS-06). */
class BS06_KitEntityCreatorTest {

    private static final String PREFIX = "http://stuff/";

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private static final UserId USER = new UserId("editor");

    private static final ProjectOntologies ONTOLOGIES = OwlApiProjectOntologies.empty();

    private final AtomicReference<NewEntitySettings> settings =
            new AtomicReference<>(NewEntitySettings.defaults(PREFIX, ONTOLOGY));

    private final KitEntityCreator creator =
            new KitEntityCreator(settings::get, () -> new PrefixedNameExpander(Map.of()));

    @Test
    void uuid_settings_mint_an_r_suffix_and_keep_the_typed_name() {
        FreshEntityIri fresh = new FreshEntityIri("My Class", "en", "disc", Set.of());

        CreatedEntity created = creator.create(EntityType.CLASS, fresh, USER, ONTOLOGIES);

        String iri = created.entity().getIRI().toString();
        assertThat(iri).startsWith(PREFIX);
        assertThat(iri.substring(PREFIX.length())).matches("R[0-9A-Za-z]+");
        assertThat(label(created).getLiteral()).isEqualTo("My Class");
        assertThat(label(created).getLang()).isEqualTo("en");
    }

    @Test
    void supplied_name_settings_transform_whitespace_and_read_a_blank_lang_as_the_default() {
        settings.set(new NewEntitySettings(
                PREFIX,
                new SuppliedNameKitSettings(WhiteSpaceTreatment.UNDERSCORES),
                NewEntitySettings.RDFS_LABEL,
                "fr",
                ONTOLOGY));
        FreshEntityIri fresh = new FreshEntityIri("My Class", "", "disc", Set.of());

        CreatedEntity created = creator.create(EntityType.CLASS, fresh, USER, ONTOLOGIES);

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "My_Class"));
        assertThat(label(created).getLiteral()).isEqualTo("My_Class");
        assertThat(label(created).getLang()).isEqualTo("fr");
    }

    @Test
    void obo_settings_are_rejected_until_that_kit_exists() {
        settings.set(new NewEntitySettings(PREFIX, new OboKitSettings(), NewEntitySettings.RDFS_LABEL, "", ONTOLOGY));
        FreshEntityIri fresh = new FreshEntityIri("Person", "en", "disc", Set.of());

        assertThatThrownBy(() -> creator.create(EntityType.CLASS, fresh, USER, ONTOLOGIES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OWLLiteral label(CreatedEntity created) {
        OWLAnnotationAssertionAxiom axiom = (OWLAnnotationAssertionAxiom)
                created.creationChanges().get(1).axiom().orElseThrow();
        return axiom.getValue().asLiteral().orElseThrow();
    }
}
