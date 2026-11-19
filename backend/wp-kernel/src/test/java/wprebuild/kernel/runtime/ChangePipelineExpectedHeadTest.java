package wprebuild.kernel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;

/**
 * ChangePipeline expected-head overload: compare head under the generation lock before generating.
 */
class ChangePipelineExpectedHeadTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-18T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    @Test
    void applies_when_expected_head_matches() {
        Harness harness = Harness.create();
        ChangePipeline pipeline = harness.pipeline();
        OWLAxiom declaration = declaration(harness);
        pipeline.apply(USER, generator(List.of(declare(), new AddAxiom(ROOT, declaration)), "seed"));

        OWLAxiom agent = harness.runtime
                .ontologies()
                .dataFactory()
                .getOWLDeclarationAxiom(harness.runtime
                        .ontologies()
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp/Agent")));
        ChangeApplicationResult<String> result = pipeline.apply(
                USER, generator(List.of(new AddAxiom(ROOT, agent)), "Added Agent"), RevisionNumber.of(1));

        assertThat(result.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.runtime.ontologies().containsAxiom(ROOT, agent)).isTrue();
        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(2))).isPresent();
    }

    @Test
    void rejects_stale_expected_head_without_committing() {
        Harness harness = Harness.create();
        ChangePipeline pipeline = harness.pipeline();
        OWLAxiom declaration = declaration(harness);
        pipeline.apply(USER, generator(List.of(declare(), new AddAxiom(ROOT, declaration)), "seed"));
        long axioms = harness.runtime.ontologies().axiomCount();

        OWLAxiom agent = harness.runtime
                .ontologies()
                .dataFactory()
                .getOWLDeclarationAxiom(harness.runtime
                        .ontologies()
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp/Agent")));
        assertThatThrownBy(() -> pipeline.apply(
                        USER, generator(List.of(new AddAxiom(ROOT, agent)), "stale"), RevisionNumber.ZERO))
                .isInstanceOf(StaleHeadException.class)
                .satisfies(thrown -> {
                    StaleHeadException stale = (StaleHeadException) thrown;
                    assertThat(stale.expected()).isEqualTo(RevisionNumber.ZERO);
                    assertThat(stale.actual()).isEqualTo(RevisionNumber.of(1));
                });

        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(harness.runtime.ontologies().axiomCount()).isEqualTo(axioms);
        assertThat(harness.runtime.ontologies().containsAxiom(ROOT, agent)).isFalse();
        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(2))).isEmpty();
    }

    @Test
    void two_argument_apply_ignores_head_checks() {
        Harness harness = Harness.create();
        ChangePipeline pipeline = harness.pipeline();
        OWLAxiom declaration = declaration(harness);

        ChangeApplicationResult<String> first =
                pipeline.apply(USER, generator(List.of(declare(), new AddAxiom(ROOT, declaration)), "first"));
        OWLAxiom agent = harness.runtime
                .ontologies()
                .dataFactory()
                .getOWLDeclarationAxiom(harness.runtime
                        .ontologies()
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp/Agent")));
        ChangeApplicationResult<String> second =
                pipeline.apply(USER, generator(List.of(new AddAxiom(ROOT, agent)), "second"));

        assertThat(first.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(1));
        assertThat(second.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(2));
    }

    private static OWLAxiom declaration(Harness harness) {
        return harness.runtime
                .ontologies()
                .dataFactory()
                .getOWLDeclarationAxiom(harness.runtime
                        .ontologies()
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp/Person")));
    }

    private static DeclareOntology declare() {
        return new DeclareOntology(ROOT, Optional.empty(), Optional.empty());
    }

    private static ChangeListGenerator<String> generator(List<OntologyChange> changes, String description) {
        return new ChangeListGenerator<>() {
            @Override
            public GeneratedChanges<String> generate(ChangeContext context) {
                return new GeneratedChanges<>(changes, "ok");
            }

            @Override
            public String describe(GeneratedChanges<String> generated, List<OntologyChange> effective) {
                return description;
            }
        };
    }

    private static final class Harness {
        private final ProjectId projectId;
        private final ProjectRuntime runtime;
        private final InMemoryRevisionLog log;

        private Harness(ProjectId projectId, ProjectRuntime runtime, InMemoryRevisionLog log) {
            this.projectId = projectId;
            this.runtime = runtime;
            this.log = log;
        }

        private static Harness create() {
            ProjectId projectId = ProjectId.random();
            ProjectRuntime runtime =
                    new ProjectRuntime(projectId, OwlApiProjectOntologies.empty(), RevisionNumber.ZERO, List.of());
            return new Harness(projectId, runtime, new InMemoryRevisionLog());
        }

        private ChangePipeline pipeline() {
            return new ChangePipeline(runtime, new ProjectWriteGate(), log, FreshIriMinter.NONE, List.of(), CLOCK);
        }
    }
}
