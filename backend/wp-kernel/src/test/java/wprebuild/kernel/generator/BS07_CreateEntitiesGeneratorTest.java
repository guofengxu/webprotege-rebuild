package wprebuild.kernel.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.crud.KitEntityCreator;
import wprebuild.kernel.crud.NewEntitySettings;
import wprebuild.kernel.crud.PrefixedNameExpander;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.CreatePermission;
import wprebuild.kernel.iri.DefaultFreshIriMinter;
import wprebuild.kernel.iri.FreshEntityIri;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;

/**
 * BS-07 create path. Parent axioms use fresh IRIs; {@link KitEntityCreator} adds the declaration
 * and label when the pipeline mints them (BS-05, BS-06).
 */
class BS07_CreateEntitiesGeneratorTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-04T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final String PREFIX = "http://example.org/";

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OWLClass PERSON = DATA.getOWLClass(IRI.create(PREFIX + "Person"));

    @Test
    void creates_two_classes_under_a_parent_with_declaration_label_and_subclass() {
        Session session = Session.open();
        session.apply(List.of(new DeclareOntology(ROOT, Optional.empty(), Optional.empty())), "Declared the ontology");
        session.apply(List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON))), "Declared Person");

        ChangeApplicationResult<List<OWLClass>> created = session.pipeline.apply(
                USER,
                new CreateEntitiesGenerator<>(
                        EntityType.CLASS, List.of("Employee", "Manager"), Optional.of("en"), Set.of(PERSON), ROOT));

        assertThat(created.result()).hasSize(2);
        assertThat(created.result()).extracting(OWLEntity::getIRI).doesNotHaveDuplicates();
        OWLClass employee = created.result().get(0);
        OWLClass manager = created.result().get(1);
        assertFormal(employee);
        assertFormal(manager);
        Revision revision = created.revision().orElseThrow();
        assertThat(revision.number()).isEqualTo(RevisionNumber.of(3));
        assertThat(revision.description()).isEqualTo("Created 2 classes");
        assertThat(revision.changes()).allSatisfy(BS07_CreateEntitiesGeneratorTest::assertNoFreshIri);
        assertThat(axioms(revision.changes(), OWLDeclarationAxiom.class)).hasSize(2);
        assertThat(axioms(revision.changes(), OWLSubClassOfAxiom.class)).hasSize(2);
        assertThat(axioms(revision.changes(), OWLAnnotationAssertionAxiom.class))
                .hasSize(2);
        assertPlaced(session, employee, "Employee");
        assertPlaced(session, manager, "Manager");
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, DATA.getOWLSubClassOfAxiom(employee, PERSON)))
                .isTrue();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, DATA.getOWLSubClassOfAxiom(manager, PERSON)))
                .isTrue();
    }

    @Test
    void empty_name_list_does_not_create_a_revision() {
        Session session = Session.open();
        session.apply(List.of(new DeclareOntology(ROOT, Optional.empty(), Optional.empty())), "Declared the ontology");

        ChangeApplicationResult<List<OWLClass>> created = session.pipeline.apply(
                USER, new CreateEntitiesGenerator<>(EntityType.CLASS, List.of(), Optional.empty(), Set.of(), ROOT));

        assertThat(created.result()).isEmpty();
        assertThat(created.effectiveChanges()).isEmpty();
        assertThat(created.revision()).isEmpty();
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    void describes_one_class_by_name_and_several_classes_by_count() {
        ChangeContext context = context();
        CreateEntitiesGenerator<OWLClass> one =
                new CreateEntitiesGenerator<>(EntityType.CLASS, List.of("Foo"), Optional.empty(), Set.of(), ROOT);
        CreateEntitiesGenerator<OWLClass> three = new CreateEntitiesGenerator<>(
                EntityType.CLASS, List.of("A", "B", "C"), Optional.empty(), Set.of(), ROOT);

        assertThat(one.describe(one.generate(context), List.of())).isEqualTo("Created class Foo");
        assertThat(three.describe(three.generate(context), List.of())).isEqualTo("Created 3 classes");
    }

    @Test
    void fresh_iris_use_a_uuid_discriminator_for_each_name() {
        GeneratedChanges<List<OWLClass>> generated = new CreateEntitiesGenerator<>(
                        EntityType.CLASS, List.of("Employee", "Employee"), Optional.of("en"), Set.of(PERSON), ROOT)
                .generate(context());

        assertThat(generated.result()).hasSize(2);
        assertThatObject(generated.result().get(0).getIRI())
                .isNotEqualTo(generated.result().get(1).getIRI());
        for (OWLClass entity : generated.result()) {
            FreshEntityIri fresh = FreshEntityIri.parse(entity.getIRI()).orElseThrow();
            assertThat(fresh.suppliedName()).isEqualTo("Employee");
            assertThat(fresh.langTag()).isEqualTo("en");
            assertThat(fresh.parentIris()).containsExactly(PERSON.getIRI());
            UUID.fromString(fresh.discriminator());
        }
        assertThat(axioms(generated.changes(), OWLSubClassOfAxiom.class)).hasSize(2);
        assertThat(axioms(generated.changes(), OWLDeclarationAxiom.class)).isEmpty();
    }

    @Test
    void places_properties_and_individuals_under_their_parents() {
        OWLObjectProperty parentObject = DATA.getOWLObjectProperty(IRI.create(PREFIX + "parentObject"));
        OWLDataProperty parentData = DATA.getOWLDataProperty(IRI.create(PREFIX + "parentData"));
        OWLAnnotationProperty parentAnnotation = DATA.getOWLAnnotationProperty(IRI.create(PREFIX + "parentAnnotation"));

        assertThat(onlyAxiom(new CreateEntitiesGenerator<>(
                        EntityType.OBJECT_PROPERTY, List.of("partOf"), Optional.empty(), Set.of(parentObject), ROOT)))
                .isInstanceOf(OWLSubObjectPropertyOfAxiom.class);
        assertThat(onlyAxiom(new CreateEntitiesGenerator<>(
                        EntityType.DATA_PROPERTY, List.of("age"), Optional.empty(), Set.of(parentData), ROOT)))
                .isInstanceOf(OWLSubDataPropertyOfAxiom.class);
        assertThat(onlyAxiom(new CreateEntitiesGenerator<>(
                        EntityType.ANNOTATION_PROPERTY,
                        List.of("altLabel"),
                        Optional.empty(),
                        Set.of(parentAnnotation),
                        ROOT)))
                .isInstanceOf(OWLSubAnnotationPropertyOfAxiom.class);
        assertThat(onlyAxiom(new CreateEntitiesGenerator<>(
                        EntityType.NAMED_INDIVIDUAL, List.of("Bob"), Optional.empty(), Set.of(PERSON), ROOT)))
                .isInstanceOf(OWLClassAssertionAxiom.class);
    }

    @Test
    void skips_owl_thing_and_anchors_the_fresh_class_with_a_declaration() {
        GeneratedChanges<List<OWLClass>> generated = new CreateEntitiesGenerator<>(
                        EntityType.CLASS, List.of("ThingChild"), Optional.empty(), Set.of(DATA.getOWLThing()), ROOT)
                .generate(context());

        assertThat(generated.changes()).hasSize(1);
        assertThat(axioms(generated.changes(), OWLDeclarationAxiom.class)).hasSize(1);
        assertThat(axioms(generated.changes(), OWLSubClassOfAxiom.class)).isEmpty();
        FreshEntityIri fresh =
                FreshEntityIri.parse(generated.result().getFirst().getIRI()).orElseThrow();
        assertThat(fresh.parentIris()).containsExactly(DATA.getOWLThing().getIRI());
    }

    @Test
    void renames_fresh_entities_to_minted_iris() {
        CreateEntitiesGenerator<OWLClass> generator =
                new CreateEntitiesGenerator<>(EntityType.CLASS, List.of("Foo"), Optional.empty(), Set.of(), ROOT);
        OWLClass fresh = generator.generate(context()).result().getFirst();
        IRI minted = IRI.create(PREFIX + "Foo");

        List<OWLClass> renamed = generator.renameResult(List.of(fresh), Map.of(fresh.getIRI(), minted));

        assertThat(renamed).containsExactly(DATA.getOWLClass(minted));
        assertThat(generator.renameResult(List.of(fresh), Map.of())).containsExactly(fresh);
    }

    @Test
    void rejects_blank_names_wrong_parents_and_unsupported_types() {
        assertThatThrownBy(() -> new CreateEntitiesGenerator<>(
                        EntityType.CLASS, List.of("  "), Optional.empty(), Set.of(), ROOT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateEntitiesGenerator<>(
                        EntityType.CLASS, List.of("Foo#Bar"), Optional.empty(), Set.of(), ROOT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateEntitiesGenerator<>(
                        EntityType.CLASS,
                        List.of("Foo"),
                        Optional.empty(),
                        Set.of(DATA.getOWLObjectProperty(IRI.create(PREFIX + "partOf"))),
                        ROOT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateEntitiesGenerator<>(
                        EntityType.DATATYPE, List.of("Foo"), Optional.empty(), Set.of(), ROOT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new CreateEntitiesGenerator<OWLClass>(null, List.of(), Optional.empty(), Set.of(), ROOT))
                .isInstanceOf(NullPointerException.class);
        CreateEntitiesGenerator<OWLClass> generator =
                new CreateEntitiesGenerator<>(EntityType.CLASS, List.of("Foo"), Optional.empty(), Set.of(), ROOT);
        assertThatThrownBy(() -> generator.generate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.describe(null, List.of())).isInstanceOf(NullPointerException.class);
        GeneratedChanges<List<OWLClass>> generated = generator.generate(context());
        assertThatThrownBy(() -> generator.describe(generated, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.renameResult(null, Map.of())).isInstanceOf(NullPointerException.class);
    }

    private static void assertPlaced(Session session, OWLClass entity, String name) {
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, DATA.getOWLDeclarationAxiom(entity)))
                .isTrue();
        List<OWLAnnotationAssertionAxiom> labels = session.runtime
                .ontologies()
                .annotationAssertions(entity.getIRI())
                .filter(axiom -> axiom.getProperty().isLabel())
                .toList();
        assertThat(labels).hasSize(1);
        OWLLiteral literal = labels.getFirst().getValue().asLiteral().orElseThrow();
        assertThat(literal.getLiteral()).isEqualTo(name);
        assertThat(literal.getLang()).isEqualTo("en");
    }

    private static void assertFormal(OWLEntity entity) {
        assertThat(FreshEntityIri.isFresh(entity.getIRI())).isFalse();
        assertThat(entity.getIRI().toString()).startsWith(PREFIX);
    }

    private static void assertNoFreshIri(OntologyChange change) {
        for (OWLEntity entity : change.signature()) {
            assertThat(FreshEntityIri.isFresh(entity.getIRI())).isFalse();
        }
    }

    private static OWLAxiom onlyAxiom(CreateEntitiesGenerator<?> generator) {
        List<OWLAxiom> axioms = axioms(generator.generate(context()).changes(), OWLAxiom.class);
        assertThat(axioms).hasSize(1);
        return axioms.getFirst();
    }

    private static <T extends OWLAxiom> List<T> axioms(List<OntologyChange> changes, Class<T> type) {
        return changes.stream()
                .map(OntologyChange::axiom)
                .flatMap(Optional::stream)
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static ChangeContext context() {
        return new ChangeContext(USER, sessionOntologies());
    }

    private static wprebuild.kernel.ontology.ProjectOntologies sessionOntologies() {
        return new ProjectRuntimeLoader(
                        new InMemoryRevisionLog(), new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), List.of())
                .load(ProjectId.random())
                .ontologies();
    }

    private static final class Session {
        private final ProjectRuntime runtime;
        private final ChangePipeline pipeline;

        private Session(ProjectRuntime runtime, ChangePipeline pipeline) {
            this.runtime = runtime;
            this.pipeline = pipeline;
        }

        private static Session open() {
            ProjectId projectId = ProjectId.random();
            InMemoryRevisionLog log = new InMemoryRevisionLog();
            ProjectRuntime runtime = new ProjectRuntimeLoader(
                            log, new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), List.of())
                    .load(projectId);
            KitEntityCreator creator = new KitEntityCreator(
                    () -> NewEntitySettings.defaults(PREFIX, ROOT), () -> new PrefixedNameExpander(Map.of()));
            ChangePipeline pipeline = new ChangePipeline(
                    runtime,
                    new ProjectWriteGate(),
                    log,
                    new DefaultFreshIriMinter(creator, CreatePermission.ALLOW_ALL),
                    List.of(),
                    CLOCK);
            return new Session(runtime, pipeline);
        }

        private void apply(List<OntologyChange> changes, String description) {
            pipeline.apply(USER, new ChangeListGenerator<String>() {
                @Override
                public GeneratedChanges<String> generate(ChangeContext ignored) {
                    return new GeneratedChanges<>(changes, "ok");
                }

                @Override
                public String describe(GeneratedChanges<String> generated, List<OntologyChange> effective) {
                    return description;
                }
            });
        }
    }
}
