package wprebuild.kernel.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
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

/** BS-14 upload merge planner and generator: new-version diff and additions-only modes. */
class BS14_UploadMergeTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-18T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final IRI ROOT_IRI = IRI.create("http://example.org/wp/root");

    private static final IRI SECOND_IRI = IRI.create("http://example.org/wp/second");

    private static final IRI OTHER_IRI = IRI.create("http://example.org/wp/other");

    private static final IRI IMPORTED = IRI.create("http://example.org/wp/imported");

    private static final OWLClass CLASS_A = DATA.getOWLClass(IRI.create("http://example.org/A"));

    private static final OWLClass CLASS_B = DATA.getOWLClass(IRI.create("http://example.org/B"));

    private static final OWLClass CLASS_C = DATA.getOWLClass(IRI.create("http://example.org/C"));

    private static final OWLAxiom AXIOM_A = DATA.getOWLDeclarationAxiom(CLASS_A);

    private static final OWLAxiom AXIOM_B = DATA.getOWLDeclarationAxiom(CLASS_B);

    private static final OWLAxiom AXIOM_C = DATA.getOWLDeclarationAxiom(CLASS_C);

    private static final OWLAnnotation COMMENT_OLD =
            DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("old"));

    private static final OWLAnnotation COMMENT_NEW =
            DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("new"));

    @Test
    void new_version_pairs_by_iri_and_diffs_axioms_and_annotations() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(
                new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                new AddAxiom(ROOT, AXIOM_A),
                new AddAxiom(ROOT, AXIOM_B),
                new AddOntologyAnnotation(ROOT, COMMENT_OLD),
                new AddImport(ROOT, IMPORTED)));

        OWLOntology uploaded = ontology(ROOT_IRI, Set.of(AXIOM_B, AXIOM_C), Set.of(COMMENT_NEW), Set.of(IMPORTED));

        UploadMergePlan plan = UploadMergePlanner.newVersion(project, List.of(uploaded));

        assertThat(plan.mode()).isEqualTo(UploadMergeMode.NEW_VERSION);
        assertThat(plan.unmatchedUploads()).isEmpty();
        assertThat(plan.patches()).hasSize(1);
        UploadMergePlan.OntologyPatch patch = plan.patches().getFirst();
        assertThat(patch.key()).isEqualTo(ROOT);
        assertThat(patch.addedAxioms()).containsExactly(AXIOM_C);
        assertThat(patch.removedAxioms()).containsExactly(AXIOM_A);
        assertThat(patch.addedAnnotations()).containsExactly(COMMENT_NEW);
        assertThat(patch.removedAnnotations()).containsExactly(COMMENT_OLD);
    }

    @Test
    void new_version_ignores_import_declaration_differences() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(
                new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                new AddAxiom(ROOT, AXIOM_A),
                new AddImport(ROOT, IMPORTED)));

        OWLOntology uploaded = ontology(ROOT_IRI, Set.of(AXIOM_A), Set.of(), Set.of());

        UploadMergePlan plan = UploadMergePlanner.newVersion(project, List.of(uploaded));

        assertThat(plan.patches()).isEmpty();
        assertThat(plan.unmatchedUploads()).isEmpty();
    }

    @Test
    void new_version_pairs_single_anonymous_ontologies_directly() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(
                List.of(new DeclareOntology(ROOT, Optional.empty(), Optional.empty()), new AddAxiom(ROOT, AXIOM_A)));

        OWLOntology uploaded = ontology(null, Set.of(AXIOM_B), Set.of(), Set.of());

        UploadMergePlan plan = UploadMergePlanner.newVersion(project, List.of(uploaded));

        assertThat(plan.patches()).hasSize(1);
        assertThat(plan.patches().getFirst().addedAxioms()).containsExactly(AXIOM_B);
        assertThat(plan.patches().getFirst().removedAxioms()).containsExactly(AXIOM_A);
        assertThat(plan.unmatchedUploads()).isEmpty();
    }

    @Test
    void new_version_lists_unmatched_uploads_when_iris_differ() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(
                new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                new AddAxiom(ROOT, AXIOM_A),
                new DeclareOntology(SECOND, Optional.of(SECOND_IRI), Optional.empty()),
                new AddAxiom(SECOND, AXIOM_B)));

        OWLOntology match = ontology(ROOT_IRI, Set.of(AXIOM_A, AXIOM_C), Set.of(), Set.of());
        OWLOntology unmatchedOne = ontology(OTHER_IRI, Set.of(AXIOM_B), Set.of(), Set.of());
        OWLOntology unmatchedTwo = ontology(null, Set.of(AXIOM_C), Set.of(), Set.of());

        UploadMergePlan plan = UploadMergePlanner.newVersion(project, List.of(match, unmatchedOne, unmatchedTwo));

        assertThat(plan.patches()).hasSize(1);
        assertThat(plan.patches().getFirst().key()).isEqualTo(ROOT);
        assertThat(plan.patches().getFirst().addedAxioms()).containsExactly(AXIOM_C);
        assertThat(plan.patches().getFirst().removedAxioms()).isEmpty();
        assertThat(plan.unmatchedUploads()).containsExactly(OTHER_IRI.toString(), "");
    }

    @Test
    void new_version_returns_empty_patches_when_identical() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(
                new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                new AddAxiom(ROOT, AXIOM_A),
                new AddOntologyAnnotation(ROOT, COMMENT_OLD)));

        OWLOntology uploaded = ontology(ROOT_IRI, Set.of(AXIOM_A), Set.of(COMMENT_OLD), Set.of());

        UploadMergePlan plan = UploadMergePlanner.newVersion(project, List.of(uploaded));

        assertThat(plan.patches()).isEmpty();
        assertThat(plan.unmatchedUploads()).isEmpty();
    }

    @Test
    void additions_only_adds_missing_axioms_and_annotations() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(
                new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                new AddAxiom(ROOT, AXIOM_A),
                new AddOntologyAnnotation(ROOT, COMMENT_OLD)));

        OWLOntology first = ontology(OTHER_IRI, Set.of(AXIOM_A, AXIOM_B), Set.of(COMMENT_NEW), Set.of());
        OWLOntology second = ontology(null, Set.of(AXIOM_C), Set.of(COMMENT_OLD), Set.of());

        UploadMergePlan plan = UploadMergePlanner.additions(project, List.of(first, second), ROOT);

        assertThat(plan.mode()).isEqualTo(UploadMergeMode.ADDITIONS);
        assertThat(plan.unmatchedUploads()).isEmpty();
        assertThat(plan.patches()).hasSize(1);
        UploadMergePlan.OntologyPatch patch = plan.patches().getFirst();
        assertThat(patch.key()).isEqualTo(ROOT);
        assertThat(patch.removedAxioms()).isEmpty();
        assertThat(patch.removedAnnotations()).isEmpty();
        assertThat(patch.addedAxioms()).containsExactlyInAnyOrder(AXIOM_B, AXIOM_C);
        assertThat(patch.addedAxioms()).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(patch.addedAnnotations()).containsExactly(COMMENT_NEW);
    }

    @Test
    void additions_rejects_a_missing_target() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty())));

        assertThatThrownBy(() -> UploadMergePlanner.additions(
                        project, List.of(ontology(null, Set.of(AXIOM_A), Set.of(), Set.of())), SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SECOND.value());
    }

    @Test
    void plan_order_is_stable_for_the_same_input() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(
                new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                new AddAxiom(ROOT, AXIOM_A),
                new AddAxiom(ROOT, AXIOM_B),
                new AddOntologyAnnotation(ROOT, COMMENT_OLD)));

        List<OWLOntology> uploaded =
                List.of(ontology(ROOT_IRI, Set.of(AXIOM_B, AXIOM_C), Set.of(COMMENT_NEW), Set.of()));

        UploadMergePlan first = UploadMergePlanner.newVersion(project, uploaded);
        UploadMergePlan second = UploadMergePlanner.newVersion(project, uploaded);

        assertThat(second).isEqualTo(first);
        assertThat(first.patches().getFirst().addedAxioms()).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(first.patches().getFirst().removedAxioms()).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(first.patches().getFirst().addedAnnotations()).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(first.patches().getFirst().removedAnnotations()).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void generator_applies_new_version_then_revert_restores_project() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                        new AddAxiom(ROOT, AXIOM_A),
                        new AddAxiom(ROOT, AXIOM_B),
                        new AddOntologyAnnotation(ROOT, COMMENT_OLD),
                        new AddImport(ROOT, IMPORTED)),
                "seed");
        OWLOntology uploaded = ontology(ROOT_IRI, Set.of(AXIOM_B, AXIOM_C), Set.of(COMMENT_NEW), Set.of());

        ChangeApplicationResult<UploadMergePlan> merged = session.pipeline.apply(
                USER, new UploadMergeGenerator(UploadMergeMode.NEW_VERSION, List.of(uploaded), Optional.empty(), ""));

        Revision revision = merged.revision().orElseThrow();
        assertThat(revision.description()).isEqualTo("Merged uploaded version");
        assertThat(revision.changes())
                .containsExactly(
                        new RemoveAxiom(ROOT, AXIOM_A),
                        new RemoveOntologyAnnotation(ROOT, COMMENT_OLD),
                        new AddAxiom(ROOT, AXIOM_C),
                        new AddOntologyAnnotation(ROOT, COMMENT_NEW));
        assertProjectMatchesUpload(session.runtime.ontologies(), ROOT, uploaded);
        assertThat(session.runtime.ontologies().containsImport(ROOT, IMPORTED)).isTrue();

        ChangeApplicationResult<RevertResult> reverted = session.pipeline.apply(
                USER, new RevertRevisionGenerator(session.projectId, revision.number(), session.log));

        assertThat(reverted.result().reverted()).isTrue();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_A)).isTrue();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_B)).isTrue();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_C)).isFalse();
        assertThat(session.runtime.ontologies().containsAnnotation(ROOT, COMMENT_OLD))
                .isTrue();
        assertThat(session.runtime.ontologies().containsAnnotation(ROOT, COMMENT_NEW))
                .isFalse();
        assertThat(session.runtime.ontologies().containsImport(ROOT, IMPORTED)).isTrue();
    }

    @Test
    void generator_applies_additions_and_keeps_existing_axioms() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                        new AddAxiom(ROOT, AXIOM_A),
                        new AddOntologyAnnotation(ROOT, COMMENT_OLD)),
                "seed");
        OWLOntology uploaded =
                ontology(OTHER_IRI, Set.of(AXIOM_A, AXIOM_B), Set.of(COMMENT_OLD, COMMENT_NEW), Set.of());

        ChangeApplicationResult<UploadMergePlan> merged = session.pipeline.apply(
                USER,
                new UploadMergeGenerator(
                        UploadMergeMode.ADDITIONS, List.of(uploaded), Optional.of(ROOT), "Appended upload"));

        assertThat(merged.revision().orElseThrow().description()).isEqualTo("Appended upload");
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_A)).isTrue();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_B)).isTrue();
        assertThat(session.runtime.ontologies().containsAnnotation(ROOT, COMMENT_OLD))
                .isTrue();
        assertThat(session.runtime.ontologies().containsAnnotation(ROOT, COMMENT_NEW))
                .isTrue();
        assertThat(merged.result().patches().getFirst().addedAxioms()).containsExactly(AXIOM_B);
        assertThat(merged.result().patches().getFirst().removedAxioms()).isEmpty();
    }

    @Test
    void generator_default_additions_description_uses_ontology_iri() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                        new AddAxiom(ROOT, AXIOM_A)),
                "seed");
        OWLOntology uploaded = ontology(null, Set.of(AXIOM_B), Set.of(), Set.of());
        UploadMergeGenerator generator =
                new UploadMergeGenerator(UploadMergeMode.ADDITIONS, List.of(uploaded), Optional.of(ROOT), "  ");

        GeneratedChanges<UploadMergePlan> generated =
                generator.generate(new ChangeContext(USER, session.runtime.ontologies()));

        assertThat(generator.describe(generated, generated.changes()))
                .isEqualTo("Merged uploaded ontologies into " + ROOT_IRI);
    }

    @Test
    void generator_default_additions_description_falls_back_to_key() {
        Session session = Session.open();
        session.apply(
                List.of(new DeclareOntology(ROOT, Optional.empty(), Optional.empty()), new AddAxiom(ROOT, AXIOM_A)),
                "seed");
        OWLOntology uploaded = ontology(null, Set.of(AXIOM_B), Set.of(), Set.of());
        UploadMergeGenerator generator =
                new UploadMergeGenerator(UploadMergeMode.ADDITIONS, List.of(uploaded), Optional.of(ROOT), "");

        GeneratedChanges<UploadMergePlan> generated =
                generator.generate(new ChangeContext(USER, session.runtime.ontologies()));

        assertThat(generator.describe(generated, generated.changes()))
                .isEqualTo("Merged uploaded ontologies into " + ROOT.value());
    }

    @Test
    void generator_produces_removes_before_adds_per_ontology() {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(
                new DeclareOntology(ROOT, Optional.of(ROOT_IRI), Optional.empty()),
                new AddAxiom(ROOT, AXIOM_A),
                new AddOntologyAnnotation(ROOT, COMMENT_OLD)));
        OWLOntology uploaded = ontology(ROOT_IRI, Set.of(AXIOM_B), Set.of(COMMENT_NEW), Set.of());
        UploadMergeGenerator generator =
                new UploadMergeGenerator(UploadMergeMode.NEW_VERSION, List.of(uploaded), Optional.empty(), "msg");

        GeneratedChanges<UploadMergePlan> generated = generator.generate(new ChangeContext(USER, project));

        assertThat(generated.changes())
                .containsExactly(
                        new RemoveAxiom(ROOT, AXIOM_A),
                        new RemoveOntologyAnnotation(ROOT, COMMENT_OLD),
                        new AddAxiom(ROOT, AXIOM_B),
                        new AddOntologyAnnotation(ROOT, COMMENT_NEW));
        assertThat(generator.describe(generated, generated.changes())).isEqualTo("msg");
    }

    private static void assertProjectMatchesUpload(ProjectOntologies project, OntologyKey key, OWLOntology uploaded) {
        assertThat(project.axioms(key).toList())
                .containsExactlyInAnyOrderElementsOf(uploaded.getAxioms(Imports.EXCLUDED));
        assertThat(project.annotations(key)).containsExactlyInAnyOrderElementsOf(uploaded.getAnnotations());
    }

    private static OWLOntology ontology(
            IRI ontologyIri, Set<OWLAxiom> axioms, Set<OWLAnnotation> annotations, Set<IRI> imports) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = ontologyIri == null ? manager.createOntology() : manager.createOntology(ontologyIri);
            manager.addAxioms(ontology, axioms);
            for (OWLAnnotation annotation : annotations) {
                manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology, annotation));
            }
            for (IRI imported : imports) {
                OWLImportsDeclaration declaration = DATA.getOWLImportsDeclaration(imported);
                manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology, declaration));
            }
            return ontology;
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalStateException("failed to build a test ontology", ex);
        }
    }

    private static final class Session {
        private final ProjectId projectId;
        private final InMemoryRevisionLog log;
        private final ProjectRuntime runtime;
        private final ChangePipeline pipeline;

        private Session(ProjectId projectId, InMemoryRevisionLog log, ProjectRuntime runtime, ChangePipeline pipeline) {
            this.projectId = projectId;
            this.log = log;
            this.runtime = runtime;
            this.pipeline = pipeline;
        }

        private static Session open() {
            ProjectId projectId = ProjectId.random();
            InMemoryRevisionLog log = new InMemoryRevisionLog();
            ProjectRuntime runtime = new ProjectRuntimeLoader(
                            log, new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), List.of())
                    .load(projectId);
            ChangePipeline pipeline =
                    new ChangePipeline(runtime, new ProjectWriteGate(), log, FreshIriMinter.NONE, List.of(), CLOCK);
            return new Session(projectId, log, runtime, pipeline);
        }

        private void apply(List<OntologyChange> changes, String description) {
            pipeline.apply(USER, new ChangeListGenerator<String>() {
                @Override
                public GeneratedChanges<String> generate(ChangeContext context) {
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
