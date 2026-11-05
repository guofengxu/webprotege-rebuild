package wprebuild.kernel.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnLineChangeCodec;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.crud.KitEntityCreator;
import wprebuild.kernel.crud.NewEntitySettings;
import wprebuild.kernel.crud.PrefixedNameExpander;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.CreatePermission;
import wprebuild.kernel.iri.DefaultFreshIriMinter;
import wprebuild.kernel.iri.FreshEntityIri;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.revision.SnapshotStore;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.DerivedView;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectSnapshotter;
import wprebuild.kernel.runtime.ProjectWriteGate;
import wprebuild.kernel.shortform.DisplayNameSetting;
import wprebuild.kernel.shortform.DisplayNameView;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * Milestone M1: import, edit, revert, snapshot, and reload the kernel without Spring or a database.
 */
@Timeout(60)
class KernelVerticalSliceTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-05T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final String PREFIX = "http://example.org/wp-test/warehouse#";

    private static final IRI WAREHOUSE = IRI.create("http://example.org/wp-test/warehouse");

    private static final IRI PALLET = IRI.create(PREFIX + "Pallet");

    private static final IRI BIN = IRI.create(PREFIX + "Bin");

    private static final List<DisplayNameSetting> NAMES = List.of(
            new DisplayNameSetting(NewEntitySettings.RDFS_LABEL, "zh"),
            new DisplayNameSetting(NewEntitySettings.RDFS_LABEL, "en"));

    @Test
    void import_edit_revert_snapshot_reload_matches() {
        ProjectWalk walk = ProjectWalk.run();

        walk.assertMatches(walk.reload(walk.log, walk.snapshots));
    }

    @Test
    void change_log_round_trip_reloads_the_same_project() {
        ProjectWalk walk = ProjectWalk.run();
        InMemoryRevisionLog persisted = walk.roundTripLog();

        walk.assertMatches(walk.reload(persisted, new InMemorySnapshotStore()));
        walk.assertMatches(walk.reload(persisted, walk.snapshots));
    }

    private static List<Supplier<DerivedView>> viewSuppliers() {
        return List.of(ClassHierarchyView::new, () -> new DisplayNameView(NAMES));
    }

    private static final class ProjectWalk {
        private final ProjectId projectId;
        private final InMemoryRevisionLog log;
        private final InMemorySnapshotStore snapshots;
        private final ProjectRuntime runtime;
        private final OWLClass created;

        private ProjectWalk(
                ProjectId projectId,
                InMemoryRevisionLog log,
                InMemorySnapshotStore snapshots,
                ProjectRuntime runtime,
                OWLClass created) {
            this.projectId = projectId;
            this.log = log;
            this.snapshots = snapshots;
            this.runtime = runtime;
            this.created = created;
        }

        private static ProjectWalk run() {
            ProjectId projectId = ProjectId.random();
            InMemoryRevisionLog log = new InMemoryRevisionLog();
            InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
            OfnZipSnapshotCodec snapshotCodec = new OfnZipSnapshotCodec();
            ProjectRuntime runtime =
                    new ProjectRuntimeLoader(log, snapshots, snapshotCodec, viewSuppliers()).load(projectId);
            ChangePipeline pipeline = pipeline(log, runtime);
            OWLOntology source = TestOntologies.load("warehouse-sample.ttl");
            OWLClass tray = edit(pipeline, runtime, projectId, log, source);
            ProjectSnapshotter.snapshot(runtime, snapshots, snapshotCodec);
            return new ProjectWalk(projectId, log, snapshots, runtime, tray);
        }

        private ProjectRuntime reload(RevisionLog revisionLog, SnapshotStore store) {
            return new ProjectRuntimeLoader(revisionLog, store, new OfnZipSnapshotCodec(), viewSuppliers())
                    .load(projectId);
        }

        private InMemoryRevisionLog roundTripLog() {
            OfnLineChangeCodec codec = new OfnLineChangeCodec();
            InMemoryRevisionLog decoded = new InMemoryRevisionLog();
            RevisionNumber expected = RevisionNumber.ZERO;
            List<Revision> stored = log.read(projectId, RevisionNumber.of(1), log.head(projectId))
                    .toList();
            for (Revision revision : stored) {
                List<OntologyChange> changes = codec.decode(codec.encode(revision.changes()));
                decoded.append(
                        projectId,
                        expected,
                        new Revision(
                                revision.number(),
                                revision.user(),
                                revision.timestamp(),
                                revision.description(),
                                changes));
                expected = revision.number();
            }
            return decoded;
        }

        private void assertMatches(ProjectRuntime reloaded) {
            assertThat(reloaded.head()).isEqualTo(runtime.head());
            assertStructure(runtime.ontologies(), reloaded.ontologies());
            assertHierarchy(runtime, reloaded);
            assertNames(runtime, reloaded);
            assertThat(reloaded.view(DisplayNameView.class).displayName(created))
                    .isEqualTo("周转托盘");
            OWLClass pallet = reloaded.ontologies().dataFactory().getOWLClass(PALLET);
            assertThat(reloaded.view(ClassHierarchyView.class).children(pallet)).contains(created);
        }
    }

    private static OWLClass edit(
            ChangePipeline pipeline,
            ProjectRuntime runtime,
            ProjectId projectId,
            InMemoryRevisionLog log,
            OWLOntology source) {
        ChangeApplicationResult<List<OntologyKey>> imported =
                pipeline.apply(USER, new InitialImportGenerator(List.of(source)));
        assertThat(imported.result()).containsExactly(OntologyKey.of(1));
        assertThat(imported.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(1));
        assertThat(imported.revision().orElseThrow().description()).isEqualTo("Imported 1 ontologies");
        assertImported(runtime.ontologies(), source);

        OWLClass pallet = runtime.ontologies().dataFactory().getOWLClass(PALLET);
        ChangeApplicationResult<List<OWLClass>> created = pipeline.apply(
                USER,
                new CreateEntitiesGenerator<>(
                        EntityType.CLASS, List.of("周转托盘"), Optional.of("zh"), Set.of(pallet), OntologyKey.of(1)));
        assertThat(created.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(2));
        OWLClass tray = created.result().getFirst();
        assertThat(tray.getIRI().toString()).startsWith(PREFIX + "R");
        assertThat(FreshEntityIri.isFresh(tray.getIRI())).isFalse();
        assertThat(runtime.view(ClassHierarchyView.class).children(pallet)).contains(tray);
        assertThat(runtime.view(DisplayNameView.class).displayName(tray)).isEqualTo("周转托盘");

        OWLClass bin = runtime.ontologies().dataFactory().getOWLClass(BIN);
        ChangeApplicationResult<Set<OWLEntity>> deleted =
                pipeline.apply(USER, new DeleteEntitiesGenerator(Set.of(bin)));
        assertThat(deleted.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(3));
        assertThat(runtime.ontologies().containsEntity(bin)).isFalse();

        ChangeApplicationResult<RevertResult> reverted =
                pipeline.apply(USER, new RevertRevisionGenerator(projectId, RevisionNumber.of(3), log));
        assertThat(reverted.result().reverted()).isTrue();
        assertThat(reverted.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(4));
        assertThat(reverted.revision().orElseThrow().description()).isEqualTo("Reverted revision 3");
        assertThat(runtime.ontologies().containsEntity(bin)).isTrue();
        assertThat(runtime.view(DisplayNameView.class).displayName(tray)).isEqualTo("周转托盘");
        assertThat(runtime.head()).isEqualTo(RevisionNumber.of(4));
        return tray;
    }

    private static ChangePipeline pipeline(InMemoryRevisionLog log, ProjectRuntime runtime) {
        KitEntityCreator creator = new KitEntityCreator(
                () -> NewEntitySettings.defaults(PREFIX, OntologyKey.of(1)), () -> new PrefixedNameExpander(Map.of()));
        return new ChangePipeline(
                runtime,
                new ProjectWriteGate(),
                log,
                new DefaultFreshIriMinter(creator, CreatePermission.ALLOW_ALL),
                List.of(),
                CLOCK);
    }

    private static void assertImported(ProjectOntologies ontologies, OWLOntology source) {
        OntologyKey root = OntologyKey.of(1);
        OWLOntologyID id = source.getOntologyID();
        assertThat(ontologies.info(root)).contains(new OntologyInfo(root, id.getOntologyIRI(), id.getVersionIRI()));
        assertThat(ontologies.axioms(root).toList()).containsExactlyInAnyOrderElementsOf(source.getAxioms());
        assertThat(ontologies.annotations(root)).containsExactlyInAnyOrderElementsOf(source.getAnnotations());
        assertThat(ontologies.imports(root))
                .containsExactlyInAnyOrderElementsOf(source.getImportsDeclarations().stream()
                        .map(OWLImportsDeclaration::getIRI)
                        .toList());
        assertThat(ontologies.info(root).orElseThrow().ontologyIri()).contains(WAREHOUSE);
    }

    private static void assertStructure(ProjectOntologies left, ProjectOntologies right) {
        assertThat(right.keys()).isEqualTo(left.keys());
        assertThat(right.axiomCount()).isEqualTo(left.axiomCount());
        for (OntologyKey key : left.keys()) {
            assertThat(right.info(key)).isEqualTo(left.info(key));
            assertThat(right.axioms(key).toList())
                    .containsExactlyInAnyOrderElementsOf(left.axioms(key).toList());
            assertThat(right.annotations(key)).isEqualTo(left.annotations(key));
            assertThat(right.imports(key)).isEqualTo(left.imports(key));
        }
    }

    private static void assertHierarchy(ProjectRuntime left, ProjectRuntime right) {
        ClassHierarchyView leftHierarchy = left.view(ClassHierarchyView.class);
        ClassHierarchyView rightHierarchy = right.view(ClassHierarchyView.class);
        assertThat(rightHierarchy.roots()).isEqualTo(leftHierarchy.roots());
        Set<OWLClass> classes = new LinkedHashSet<>();
        classes.add(left.ontologies().dataFactory().getOWLThing());
        left.ontologies()
                .signature()
                .filter(OWLEntity::isOWLClass)
                .map(OWLEntity::asOWLClass)
                .forEach(classes::add);
        right.ontologies()
                .signature()
                .filter(OWLEntity::isOWLClass)
                .map(OWLEntity::asOWLClass)
                .forEach(classes::add);
        for (OWLClass cls : classes) {
            assertThat(rightHierarchy.children(cls)).isEqualTo(leftHierarchy.children(cls));
        }
    }

    private static void assertNames(ProjectRuntime left, ProjectRuntime right) {
        DisplayNameView leftNames = left.view(DisplayNameView.class);
        DisplayNameView rightNames = right.view(DisplayNameView.class);
        List<OWLEntity> entities = new ArrayList<>();
        left.ontologies().signature().forEach(entities::add);
        entities.sort(Comparator.comparing(entity -> entity.getIRI().toString()));
        List<OWLEntity> reloaded = new ArrayList<>();
        right.ontologies().signature().forEach(reloaded::add);
        assertThat(reloaded).containsExactlyInAnyOrderElementsOf(entities);
        for (OWLEntity entity : entities) {
            assertThat(rightNames.displayName(entity)).isEqualTo(leftNames.displayName(entity));
        }
    }
}
