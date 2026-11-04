package wprebuild.kernel.crud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;

import java.util.List;
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
    void obo_settings_mint_the_id_after_the_range_start_and_keep_the_typed_name() {
        settings.set(new NewEntitySettings(
                PREFIX,
                new OboKitSettings(3, List.of(new UserIdRange(USER, 10, 12))),
                NewEntitySettings.RDFS_LABEL,
                "en",
                ONTOLOGY));
        FreshEntityIri fresh = new FreshEntityIri("Person", "en", "disc", Set.of());

        CreatedEntity first = creator.create(EntityType.CLASS, fresh, USER, ONTOLOGIES);
        CreatedEntity second = creator.create(EntityType.CLASS, fresh, USER, ONTOLOGIES);

        assertThatObject(first.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "011"));
        assertThatObject(second.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "012"));
        assertThat(label(first).getLiteral()).isEqualTo("Person");
        assertThat(label(first).getLang()).isEqualTo("en");
    }

    private static OWLLiteral label(CreatedEntity created) {
        OWLAnnotationAssertionAxiom axiom = (OWLAnnotationAssertionAxiom)
                created.creationChanges().get(1).axiom().orElseThrow();
        return axiom.getValue().asLiteral().orElseThrow();
    }
}
