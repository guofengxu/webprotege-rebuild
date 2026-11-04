package wprebuild.kernel.crud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * OBO numeric IRIs (BS-06).
 *
 * <p>The old handler stores the range start and increments before issuing, so a range of
 * {@code [start, end]} yields {@code start + 1} through {@code end}. Users with no range share one
 * counter that starts at 1 and has no end.
 */
class BS06_OboIdEntityCrudKitTest {

    private static final String PREFIX = "http://purl.obolibrary.org/obo/ONT_";

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private static final OntologyKey OTHER = OntologyKey.of(2);

    private static final UserId ALICE = new UserId("alice");

    private static final UserId BOB = new UserId("bob");

    private static final IRI OWL_THING = IRI.create("http://www.w3.org/2002/07/owl#Thing");

    private final PrefixedNameExpander expander = new PrefixedNameExpander(Map.of());

    @Test
    void defaults_are_seven_digits_and_no_user_ranges() {
        OboKitSettings settings = OboKitSettings.defaults();

        assertThat(settings.totalDigits()).isEqualTo(7);
        assertThat(settings.userRanges()).isEmpty();
    }

    @Test
    void rejects_a_non_positive_width_a_reversed_range_and_a_duplicate_user() {
        UserIdRange range = new UserIdRange(ALICE, 10, 12);

        assertThatThrownBy(() -> new OboKitSettings(0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserIdRange(ALICE, 5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserIdRange(ALICE, -1, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OboKitSettings(7, List.of(range, range)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issues_the_id_after_the_range_start_then_the_rest_of_the_range() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());

        assertThatObject(create(kit, "Alpha").entity().getIRI()).isEqualTo(IRI.create(PREFIX + "011"));
        assertThatObject(create(kit, "Beta").entity().getIRI()).isEqualTo(IRI.create(PREFIX + "012"));
        assertThatThrownBy(() -> create(kit, "Gamma"))
                .isInstanceOf(CannotGenerateFreshEntityIdException.class)
                .isInstanceOfSatisfying(CannotGenerateFreshEntityIdException.class, failure -> {
                    assertThat(failure.userId()).isEqualTo(ALICE);
                    assertThat(failure.range()).isEqualTo(range(ALICE, 10, 12));
                });
    }

    @Test
    void keeps_digits_that_do_not_fit_in_the_padding_width() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());
        OboKitSettings settings = new OboKitSettings(3, List.of(range(ALICE, 998, 1000)));

        assertThatObject(create(kit, "Near", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "999"));
        assertThatObject(create(kit, "Wide", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "1000"));
    }

    @Test
    void a_range_whose_start_equals_its_end_is_already_exhausted() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());
        OboKitSettings settings = new OboKitSettings(3, List.of(range(ALICE, 5, 5)));

        assertThatThrownBy(() -> create(kit, "Alone", settings))
                .isInstanceOf(CannotGenerateFreshEntityIdException.class);
    }

    @Test
    void skips_an_id_already_used_anywhere_in_the_project_signature() {
        ProjectOntologies ontologies = withClass(OTHER, IRI.create(PREFIX + "011"));
        OboIdEntityCrudKit kit = kit(ALICE, ontologies);

        CreatedEntity created = create(kit, "Beta");

        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "012"));
        assertThat(created.creationChanges().get(0).ontology()).isEqualTo(ONTOLOGY);
    }

    @Test
    void does_not_reuse_an_id_this_kit_already_issued_before_it_is_in_the_signature() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());

        IRI first = create(kit, "Alpha").entity().getIRI();
        IRI second = create(kit, "Beta").entity().getIRI();

        assertThatObject(first).isEqualTo(IRI.create(PREFIX + "011"));
        assertThatObject(second).isEqualTo(IRI.create(PREFIX + "012"));
    }

    @Test
    void a_user_without_a_range_starts_at_one_and_shares_that_counter() {
        ProjectOntologies ontologies = ontologies();
        OboKitSettings settings = OboKitSettings.defaults();
        OboIdAllocator shared = new OboIdAllocator();
        OboIdEntityCrudKit bob = new OboIdEntityCrudKit(BOB, ontologies, shared);
        OboIdEntityCrudKit alice = new OboIdEntityCrudKit(ALICE, ontologies, shared);

        assertThatObject(create(bob, "Bob", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "0000001"));
        assertThatObject(create(alice, "Alice", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "0000002"));
    }

    @Test
    void a_user_without_a_range_skips_used_ids_and_is_not_capped_by_someone_elses_range() {
        ProjectOntologies ontologies = withClass(ONTOLOGY, IRI.create(PREFIX + "0000001"));
        OboKitSettings settings = new OboKitSettings(7, List.of(range(ALICE, 1, 1)));
        OboIdAllocator shared = new OboIdAllocator();
        OboIdEntityCrudKit alice = new OboIdEntityCrudKit(ALICE, ontologies, shared);
        OboIdEntityCrudKit bob = new OboIdEntityCrudKit(BOB, ontologies, shared);

        assertThatThrownBy(() -> create(alice, "Alice", settings))
                .isInstanceOf(CannotGenerateFreshEntityIdException.class);
        assertThatObject(create(bob, "Bob", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "0000002"));
        assertThatObject(create(bob, "Bobby", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "0000003"));
    }

    @Test
    void a_changed_range_or_prefix_starts_again_without_repeating_an_iri_from_this_allocator() {
        OboIdAllocator shared = new OboIdAllocator();
        OboIdEntityCrudKit kit = new OboIdEntityCrudKit(ALICE, ontologies(), shared);
        OboKitSettings first = new OboKitSettings(3, List.of(range(ALICE, 10, 12)));
        OboKitSettings widened = new OboKitSettings(3, List.of(range(ALICE, 20, 22)));
        String otherPrefix = "http://example.org/NEW_";

        assertThatObject(create(kit, "A", first).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "011"));
        assertThatObject(create(kit, "B", first).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "012"));
        assertThatObject(create(kit, "C", widened).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "021"));

        NewEntitySettings moved =
                new NewEntitySettings(otherPrefix, first, NewEntitySettings.RDFS_LABEL, "en", ONTOLOGY);
        CreatedEntity other = kit.create(EntityType.CLASS, "D", Optional.of("en"), moved, expander);
        OboKitSettings room = new OboKitSettings(3, List.of(range(ALICE, 10, 14)));
        CreatedEntity back = create(kit, "E", room);

        assertThatObject(other.entity().getIRI()).isEqualTo(IRI.create(otherPrefix + "011"));
        assertThatObject(back.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "013"));
    }

    @Test
    void an_id_minted_for_one_user_is_not_reissued_to_another_before_it_is_in_the_signature() {
        ProjectOntologies ontologies = ontologies();
        OboKitSettings settings = new OboKitSettings(3, List.of(range(ALICE, 0, 3)));
        OboIdAllocator shared = new OboIdAllocator();
        OboIdEntityCrudKit alice = new OboIdEntityCrudKit(ALICE, ontologies, shared);
        OboIdEntityCrudKit bob = new OboIdEntityCrudKit(BOB, ontologies, shared);

        assertThatObject(create(alice, "Alice", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "001"));
        assertThatObject(create(bob, "Bob", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "002"));
    }

    @Test
    void a_ranged_user_does_not_take_the_unassigned_counter() {
        ProjectOntologies ontologies = ontologies();
        OboKitSettings settings = new OboKitSettings(3, List.of(range(ALICE, 10, 12)));
        OboIdAllocator shared = new OboIdAllocator();
        OboIdEntityCrudKit alice = new OboIdEntityCrudKit(ALICE, ontologies, shared);
        OboIdEntityCrudKit bob = new OboIdEntityCrudKit(BOB, ontologies, shared);

        assertThatObject(create(alice, "Alpha", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "011"));
        assertThatObject(create(bob, "Bob", settings).entity().getIRI()).isEqualTo(IRI.create(PREFIX + "001"));
    }

    @Test
    void non_empty_name_adds_a_declaration_and_a_label_in_the_requested_language() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());

        CreatedEntity created = kit.create(EntityType.CLASS, "My Class", Optional.of("en"), settings(3), expander);

        assertThat(created.creationChanges()).hasSize(2);
        assertDeclaration(created);
        OWLLiteral label = labelLiteral(created);
        assertThat(label.getLiteral()).isEqualTo("My Class");
        assertThat(label.getLang()).isEqualTo("en");
        assertThatObject(labelAxiom(created).getProperty().getIRI()).isEqualTo(NewEntitySettings.RDFS_LABEL);
    }

    @Test
    void absent_language_uses_the_default_language() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());
        NewEntitySettings settings = new NewEntitySettings(
                PREFIX, obo(range(ALICE, 10, 12), 3), NewEntitySettings.RDFS_LABEL, "de", ONTOLOGY);

        CreatedEntity created = kit.create(EntityType.CLASS, "Person", Optional.empty(), settings, expander);

        assertThat(labelLiteral(created).getLang()).isEqualTo("de");
    }

    @Test
    void blank_name_does_not_add_a_label() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());

        CreatedEntity created = kit.create(EntityType.CLASS, "   ", Optional.of("en"), settings(3), expander);

        assertThat(created.creationChanges()).hasSize(1);
        assertDeclaration(created);
        assertThatObject(created.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "011"));
    }

    @Test
    void quoted_iri_and_prefixed_name_are_used_directly_and_do_not_consume_an_id() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());

        CreatedEntity quoted = kit.create(EntityType.CLASS, "<http://x/y>", Optional.of("en"), settings(3), expander);
        CreatedEntity prefixed = kit.create(EntityType.CLASS, "owl:Thing", Optional.of("en"), settings(3), expander);
        CreatedEntity minted = create(kit, "Person");

        assertThatObject(quoted.entity().getIRI()).isEqualTo(IRI.create("http://x/y"));
        assertThat(labelLiteral(quoted).getLiteral()).isEqualTo("http://x/y");
        assertThatObject(prefixed.entity().getIRI()).isEqualTo(OWL_THING);
        assertThat(labelLiteral(prefixed).getLiteral()).isEqualTo("owl:Thing");
        assertThatObject(minted.entity().getIRI()).isEqualTo(IRI.create(PREFIX + "011"));
    }

    @Test
    void creates_the_requested_entity_type() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());

        NewEntitySettings settings =
                new NewEntitySettings(PREFIX, obo(range(ALICE, 10, 12), 3), NewEntitySettings.RDFS_LABEL, "", ONTOLOGY);
        CreatedEntity created = kit.create(EntityType.OBJECT_PROPERTY, "partOf", Optional.empty(), settings, expander);

        assertThat(created.entity()).isInstanceOf(OWLObjectProperty.class);
        assertThat(labelLiteral(created).getLang()).isEmpty();
    }

    @Test
    void refuses_settings_for_another_kit() {
        OboIdEntityCrudKit kit = kit(ALICE, ontologies());
        NewEntitySettings uuid = NewEntitySettings.defaults(PREFIX, ONTOLOGY);

        assertThatThrownBy(() -> kit.create(EntityType.CLASS, "Person", Optional.empty(), uuid, expander))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OboIdEntityCrudKit kit(UserId user, ProjectOntologies ontologies) {
        return new OboIdEntityCrudKit(user, ontologies, new OboIdAllocator());
    }

    private CreatedEntity create(OboIdEntityCrudKit kit, String name) {
        return create(kit, name, obo(range(ALICE, 10, 12), 3));
    }

    private CreatedEntity create(OboIdEntityCrudKit kit, String name, OboKitSettings obo) {
        return kit.create(
                EntityType.CLASS,
                name,
                Optional.of("en"),
                new NewEntitySettings(PREFIX, obo, NewEntitySettings.RDFS_LABEL, "en", ONTOLOGY),
                expander);
    }

    private NewEntitySettings settings(int digits) {
        return new NewEntitySettings(
                PREFIX, obo(range(ALICE, 10, 12), digits), NewEntitySettings.RDFS_LABEL, "en", ONTOLOGY);
    }

    private static OboKitSettings obo(UserIdRange range, int digits) {
        return new OboKitSettings(digits, List.of(range));
    }

    private static UserIdRange range(UserId user, long start, long end) {
        return new UserIdRange(user, start, end);
    }

    private static ProjectOntologies ontologies() {
        return OwlApiProjectOntologies.empty();
    }

    private static ProjectOntologies withClass(OntologyKey key, IRI iri) {
        OwlApiProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(
                new DeclareOntology(ONTOLOGY, Optional.empty(), Optional.empty()),
                new DeclareOntology(OTHER, Optional.empty(), Optional.empty())));
        OWLClass owlClass = ontologies.dataFactory().getOWLClass(iri);
        ontologies.apply(List.of(new AddAxiom(key, ontologies.dataFactory().getOWLDeclarationAxiom(owlClass))));
        return ontologies;
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
