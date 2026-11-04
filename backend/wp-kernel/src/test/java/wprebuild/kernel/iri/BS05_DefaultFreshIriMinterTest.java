package wprebuild.kernel.iri;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.FreshIriMinter;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;

/** Fresh IRI minting, replacement, and the write-pipeline rename (BS-05, WP-7). */
class BS05_DefaultFreshIriMinterTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final UserId USER = new UserId("editor");

    private static final IRI PARENT = IRI.create("http://example.org/Parent");

    private static final FreshEntityIri FRESH = new FreshEntityIri("Person", "en", "disc-1", Set.of(PARENT));

    private static final IRI FRESH_IRI = FRESH.toIri();

    private static final SnapshotCodec UNUSED_CODEC = new SnapshotCodec() {
        @Override
        public String id() {
            return "unused";
        }

        @Override
        public byte[] encode(ProjectOntologies ontologies) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MutableProjectOntologies decode(byte[] payload) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void replaces_iris_in_axioms_annotations_and_annotation_subjects() {
        IRI minted = IRI.create("http://example.org/Person");
        OWLClass freshClass = DATA.getOWLClass(FRESH_IRI);
        OWLAxiom subclass = DATA.getOWLSubClassOfAxiom(freshClass, DATA.getOWLThing());
        OWLAxiom assertion =
                DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSLabel(), FRESH_IRI, DATA.getOWLLiteral("Person", "en"));
        List<OntologyChange> input = List.of(
                new AddAxiom(ROOT, subclass),
                new RemoveAxiom(ROOT, subclass),
                new AddOntologyAnnotation(ROOT, DATA.getOWLAnnotation(DATA.getRDFSComment(), FRESH_IRI)),
                new AddAxiom(ROOT, assertion),
                new AddImport(ROOT, FRESH_IRI),
                new DeclareOntology(ROOT, Optional.of(FRESH_IRI), Optional.empty()));

        List<OntologyChange> replaced = IriReplacer.replace(input, Map.of(FRESH_IRI, minted));

        assertThat(((AddAxiom) replaced.get(0)).owlAxiom().getSignature()).contains(DATA.getOWLClass(minted));
        assertThat(((AddAxiom) replaced.get(0)).owlAxiom().getSignature()).doesNotContain(freshClass);
        assertThat(((RemoveAxiom) replaced.get(1)).owlAxiom().getSignature()).contains(DATA.getOWLClass(minted));
        assertThat(((AddOntologyAnnotation) replaced.get(2))
                        .annotation()
                        .getValue()
                        .asIRI())
                .contains(minted);
        OWLAnnotationAssertionAxiom renamed = (OWLAnnotationAssertionAxiom) ((AddAxiom) replaced.get(3)).owlAxiom();
        assertThat(renamed.getSubject()).isEqualTo(minted);
        assertThatObject(((AddImport) replaced.get(4)).iri()).isEqualTo(minted);
        assertThat(((DeclareOntology) replaced.get(5)).ontologyIri()).contains(minted);
        assertThat(IriReplacer.replace(input, Map.of())).containsExactlyElementsOf(input);
        assertThatThrownBy(() -> IriReplacer.replace(null, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IriReplacer.replace(List.of(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void keeps_anonymous_individual_ids_while_replacing_a_fresh_iri() {
        IRI minted = IRI.create("http://example.org/Person");
        OWLAnonymousIndividual anonymous = DATA.getOWLAnonymousIndividual("genid1");
        OWLAxiom assertion = DATA.getOWLClassAssertionAxiom(DATA.getOWLThing(), anonymous);
        List<OntologyChange> input = List.of(
                new AddAxiom(ROOT, assertion),
                new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(FRESH_IRI))));

        List<OntologyChange> replaced = IriReplacer.replace(input, Map.of(FRESH_IRI, minted));

        assertThat(((AddAxiom) replaced.get(0)).owlAxiom()).isEqualTo(assertion);
        assertThat(((AddAxiom) replaced.get(1)).owlAxiom().getSignature()).contains(DATA.getOWLClass(minted));
    }

    @Test
    void creates_one_entity_when_the_same_fresh_iri_is_reused() {
        AtomicInteger calls = new AtomicInteger();
        IRI mintedIri = IRI.create("http://example.org/minted");
        List<OntologyChange> creation =
                List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(mintedIri))));
        ProjectOntologies ontologies = emptyOntologies();
        EntityCreator creator = (type, fresh, user, seen) -> {
            calls.incrementAndGet();
            assertThat(type).isEqualTo(EntityType.CLASS);
            assertThat(fresh).isEqualTo(FRESH);
            assertThat(user).isEqualTo(USER);
            assertThat(seen).isSameAs(ontologies);
            return new EntityCreator.CreatedEntity(DATA.getOWLClass(mintedIri), creation);
        };
        OWLClass freshClass = DATA.getOWLClass(FRESH_IRI);
        List<OntologyChange> input = List.of(
                new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(freshClass, DATA.getOWLThing())),
                new AddAxiom(
                        ROOT,
                        DATA.getOWLEquivalentClassesAxiom(
                                freshClass, DATA.getOWLClass(IRI.create("http://example.org/Agent")))),
                new AddAxiom(
                        ROOT,
                        DATA.getOWLAnnotationAssertionAxiom(
                                DATA.getRDFSLabel(), FRESH_IRI, DATA.getOWLLiteral("Person", "en"))),
                new AddOntologyAnnotation(ROOT, DATA.getOWLAnnotation(DATA.getRDFSComment(), FRESH_IRI)));

        FreshIriMinter.MintResult minted =
                new DefaultFreshIriMinter(creator, CreatePermission.ALLOW_ALL).mint(USER, input, ontologies);

        assertThat(calls).hasValue(1);
        assertThat(CreatePermission.ALLOW_ALL.canCreate(USER, EntityType.CLASS)).isTrue();
        assertThat(minted.renameMap()).containsOnlyKeys(FRESH_IRI);
        assertThatObject(minted.renameMap().get(FRESH_IRI)).isEqualTo(mintedIri);
        assertThat(minted.changes()).hasSize(input.size() + creation.size());
        assertThat(minted.changes().subList(input.size(), minted.changes().size()))
                .containsExactlyElementsOf(creation);
        assertThat(minted.changes().subList(0, input.size())).allSatisfy(BS05_DefaultFreshIriMinterTest::assertNoFresh);
        OWLAnnotationAssertionAxiom assertion =
                (OWLAnnotationAssertionAxiom) ((AddAxiom) minted.changes().get(3)).owlAxiom();
        assertThat(assertion.getSubject()).isEqualTo(mintedIri);
        assertThat(((AddOntologyAnnotation) minted.changes().get(4))
                        .annotation()
                        .getValue()
                        .asIRI())
                .contains(mintedIri);
    }

    @Test
    void mints_each_distinct_fresh_iri_once() {
        AtomicInteger calls = new AtomicInteger();
        EntityCreator creator = (type, fresh, user, ontologies) -> {
            calls.incrementAndGet();
            IRI iri = IRI.create("http://example.org/minted-" + fresh.suppliedName());
            return new EntityCreator.CreatedEntity(DATA.getOWLClass(iri), List.of());
        };
        FreshEntityIri second = new FreshEntityIri("Agent", "en", "disc-2", Set.of());
        List<OntologyChange> input = List.of(
                new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(FRESH_IRI))),
                new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(FRESH_IRI))),
                new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(second.toIri()))));

        FreshIriMinter.MintResult minted =
                new DefaultFreshIriMinter(creator, CreatePermission.ALLOW_ALL).mint(USER, input, emptyOntologies());

        assertThat(calls).hasValue(2);
        assertThat(minted.renameMap()).containsOnlyKeys(FRESH_IRI, second.toIri());
        assertThat(minted.changes()).allSatisfy(BS05_DefaultFreshIriMinterTest::assertNoFresh);
    }

    @Test
    void throws_when_create_permission_is_denied() {
        AtomicInteger calls = new AtomicInteger();
        EntityCreator creator = (type, fresh, user, ontologies) -> {
            calls.incrementAndGet();
            throw new AssertionError("creator must not run");
        };
        List<OntologyChange> input =
                List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(FRESH_IRI))));
        CreatePermission denyClasses = (user, type) -> !EntityType.CLASS.equals(type);

        assertThatThrownBy(() -> new DefaultFreshIriMinter(creator, denyClasses).mint(USER, input, emptyOntologies()))
                .isInstanceOf(CreatePermissionDeniedException.class)
                .satisfies(error -> {
                    CreatePermissionDeniedException denied = (CreatePermissionDeniedException) error;
                    assertThat(denied.user()).isEqualTo(USER);
                    assertThat(denied.entityType()).isEqualTo(EntityType.CLASS);
                });
        assertThat(calls).hasValue(0);
    }

    @Test
    void leaves_ordinary_changes_unchanged() {
        AtomicInteger calls = new AtomicInteger();
        EntityCreator creator = (type, fresh, user, ontologies) -> {
            calls.incrementAndGet();
            throw new AssertionError("creator must not run");
        };
        List<OntologyChange> input = List.of(
                new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                new AddAxiom(
                        ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(IRI.create("http://example.org/Person")))));

        FreshIriMinter.MintResult minted =
                new DefaultFreshIriMinter(creator, CreatePermission.ALLOW_ALL).mint(USER, input, emptyOntologies());

        assertThat(calls).hasValue(0);
        assertThat(minted.renameMap()).isEmpty();
        assertThat(minted.changes()).containsExactlyElementsOf(input);
    }

    @Test
    void rejects_a_fresh_annotation_subject_with_no_entity_type() {
        List<OntologyChange> input = List.of(new AddAxiom(
                ROOT,
                DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSLabel(), FRESH_IRI, DATA.getOWLLiteral("Person"))));

        assertThatThrownBy(() -> new DefaultFreshIriMinter(unusedCreator(), CreatePermission.ALLOW_ALL)
                        .mint(USER, input, emptyOntologies()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no entity type");
    }

    @Test
    void rejects_a_fresh_iri_used_as_two_entity_types() {
        List<OntologyChange> input = List.of(
                new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(FRESH_IRI))),
                new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLNamedIndividual(FRESH_IRI))));

        assertThatThrownBy(() -> new DefaultFreshIriMinter(unusedCreator(), CreatePermission.ALLOW_ALL)
                        .mint(USER, input, emptyOntologies()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_created_entity_of_the_wrong_type_or_with_a_fresh_iri() {
        assertThatThrownBy(() -> new EntityCreator.CreatedEntity(DATA.getOWLClass(FRESH_IRI), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        EntityCreator wrongType = (type, fresh, user, ontologies) -> new EntityCreator.CreatedEntity(
                DATA.getOWLNamedIndividual(IRI.create("http://example.org/Person")), List.of());
        List<OntologyChange> input =
                List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(FRESH_IRI))));

        assertThatThrownBy(() -> new DefaultFreshIriMinter(wrongType, CreatePermission.ALLOW_ALL)
                        .mint(USER, input, emptyOntologies()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_creation_change_that_still_uses_a_fresh_iri() {
        FreshEntityIri other = new FreshEntityIri("Other", "", "disc", Set.of());
        EntityCreator creator = (type, fresh, user, ontologies) -> new EntityCreator.CreatedEntity(
                DATA.getOWLClass(IRI.create("http://example.org/Person")),
                List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(other.toIri())))));
        List<OntologyChange> input =
                List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(FRESH_IRI))));

        assertThatThrownBy(() -> new DefaultFreshIriMinter(creator, CreatePermission.ALLOW_ALL)
                        .mint(USER, input, emptyOntologies()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remains after minting");
    }

    @Test
    void rejects_a_malformed_fresh_iri() {
        IRI malformed = IRI.create("entity:placeholder");
        List<OntologyChange> input =
                List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(DATA.getOWLClass(malformed))));

        assertThatThrownBy(() -> new DefaultFreshIriMinter(unusedCreator(), CreatePermission.ALLOW_ALL)
                        .mint(USER, input, emptyOntologies()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pipeline_stores_no_fresh_iri_and_renames_the_result() {
        AtomicInteger calls = new AtomicInteger();
        IRI mintedIri = IRI.create("http://example.org/wp/Person");
        OWLClass mintedClass = DATA.getOWLClass(mintedIri);
        EntityCreator creator = (type, fresh, user, ontologies) -> {
            calls.incrementAndGet();
            return new EntityCreator.CreatedEntity(
                    mintedClass, List.of(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(mintedClass))));
        };
        ProjectId projectId = ProjectId.random();
        InMemoryRevisionLog log = new InMemoryRevisionLog();
        ProjectRuntime runtime =
                new ProjectRuntimeLoader(log, new InMemorySnapshotStore(), UNUSED_CODEC, List.of()).load(projectId);
        ChangePipeline pipeline = new ChangePipeline(
                runtime,
                new ProjectWriteGate(),
                log,
                new DefaultFreshIriMinter(creator, CreatePermission.ALLOW_ALL),
                List.of(),
                Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC));
        OWLClass freshClass = DATA.getOWLClass(FRESH_IRI);
        List<OntologyChange> changes = List.of(
                new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(freshClass, DATA.getOWLThing())),
                new AddAxiom(
                        ROOT,
                        DATA.getOWLAnnotationAssertionAxiom(
                                DATA.getRDFSLabel(), FRESH_IRI, DATA.getOWLLiteral("Person", "en"))));
        ChangeListGenerator<Labelled> generator = new ChangeListGenerator<>() {
            @Override
            public GeneratedChanges<Labelled> generate(ChangeContext context) {
                return new GeneratedChanges<>(changes, new Labelled(FRESH_IRI));
            }

            @Override
            public Labelled renameResult(Labelled result, Map<IRI, IRI> renameMap) {
                return new Labelled(renameMap.getOrDefault(result.iri(), result.iri()));
            }
        };

        ChangeApplicationResult<Labelled> applied = pipeline.apply(USER, generator);

        assertThat(calls).hasValue(1);
        assertThat(applied.result()).isEqualTo(new Labelled(mintedIri));
        assertThat(applied.renameMap()).containsOnlyKeys(FRESH_IRI);
        Revision revision = applied.revision().orElseThrow();
        assertThat(revision.changes()).isNotEmpty().allSatisfy(BS05_DefaultFreshIriMinterTest::assertNoFresh);
        assertThat(applied.effectiveChanges()).allSatisfy(BS05_DefaultFreshIriMinterTest::assertNoFresh);
        assertThat(runtime.ontologies().containsEntity(mintedClass)).isTrue();
        assertThat(runtime.ontologies().containsEntity(freshClass)).isFalse();
        assertThat(runtime.ontologies().annotationAssertions(mintedIri)).isNotEmpty();
        assertThatThrownBy(() -> new DefaultFreshIriMinter(null, CreatePermission.ALLOW_ALL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultFreshIriMinter(creator, null)).isInstanceOf(NullPointerException.class);
    }

    private static ProjectOntologies emptyOntologies() {
        return new ProjectRuntimeLoader(new InMemoryRevisionLog(), new InMemorySnapshotStore(), UNUSED_CODEC, List.of())
                .load(ProjectId.random())
                .ontologies();
    }

    private static EntityCreator unusedCreator() {
        return (type, fresh, user, ontologies) -> {
            throw new AssertionError("creator must not run");
        };
    }

    private static void assertNoFresh(OntologyChange change) {
        for (OWLEntity entity : change.signature()) {
            assertThat(FreshEntityIri.isFresh(entity.getIRI())).isFalse();
        }
        if (change instanceof AddAxiom add && add.owlAxiom() instanceof OWLAnnotationAssertionAxiom assertion) {
            if (assertion.getSubject() instanceof IRI iri) {
                assertThat(FreshEntityIri.isFresh(iri)).isFalse();
            }
        }
        if (change instanceof AddOntologyAnnotation add && add.annotation().getValue() instanceof IRI iri) {
            assertThat(FreshEntityIri.isFresh(iri)).isFalse();
        }
        if (change instanceof AddImport add) {
            assertThat(FreshEntityIri.isFresh(add.iri())).isFalse();
        }
        if (change instanceof DeclareOntology declare) {
            declare.ontologyIri()
                    .ifPresent(iri -> assertThat(FreshEntityIri.isFresh(iri)).isFalse());
            declare.versionIri()
                    .ifPresent(iri -> assertThat(FreshEntityIri.isFresh(iri)).isFalse());
        }
    }

    private record Labelled(IRI iri) {}
}
