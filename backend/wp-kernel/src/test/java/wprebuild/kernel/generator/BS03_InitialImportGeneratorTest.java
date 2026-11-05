package wprebuild.kernel.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.FreshIriMinter;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * BS-03 initial import. Each parsed ontology becomes one key, then its axioms, ontology
 * annotations, and import declarations.
 */
class BS03_InitialImportGeneratorTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-05T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("importer");

    private static final List<String> IMPORT_FILES = List.of("imports/a.owl", "imports/b.owl", "imports/main.owl");

    private static final OWLClass COMPONENT_A =
            OWLManager.getOWLDataFactory().getOWLClass(IRI.create("http://example.org/wp-test/imports/a#ComponentA"));

    @Test
    void imports_three_ontologies_with_keys_axioms_and_imports() {
        List<OWLOntology> sources = load(IMPORT_FILES);
        Session session = Session.open();

        ChangeApplicationResult<List<OntologyKey>> imported =
                session.pipeline.apply(USER, new InitialImportGenerator(sources));

        Revision revision = imported.revision().orElseThrow();
        assertThat(imported.result()).containsExactly(OntologyKey.of(1), OntologyKey.of(2), OntologyKey.of(3));
        assertThat(revision.number()).isEqualTo(RevisionNumber.of(1));
        assertThat(revision.description()).isEqualTo("Imported 3 ontologies");
        assertThat(revision.user()).isEqualTo(USER);
        assertThat(revision.timestamp()).isEqualTo(TIMESTAMP);
        assertGrouped(revision.changes(), sources);
        assertApplied(session.runtime.ontologies(), sources);
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    void rejects_import_when_the_project_is_not_empty() {
        List<OWLOntology> sources = load(IMPORT_FILES);
        Session session = Session.open();
        session.pipeline.apply(USER, new InitialImportGenerator(sources));
        long axioms = session.runtime.ontologies().axiomCount();

        assertThatThrownBy(() -> session.pipeline.apply(USER, new InitialImportGenerator(List.of(sources.getFirst()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not empty");

        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(session.runtime.ontologies().keys())
                .containsExactlyInAnyOrder(OntologyKey.of(1), OntologyKey.of(2), OntologyKey.of(3));
        assertThat(session.runtime.ontologies().axiomCount()).isEqualTo(axioms);
        assertThat(session.log.get(session.projectId, RevisionNumber.of(2))).isEmpty();
    }

    @Test
    void rejects_duplicate_document_iris_without_committing() {
        IRI shared = IRI.create("http://example.org/wp-test/shared");
        Session sameId = Session.open();
        assertThatThrownBy(() -> sameId.pipeline.apply(
                        USER, new InitialImportGenerator(List.of(ontology(shared, null), ontology(shared, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(shared.toString());
        assertUnchanged(sameId);

        IRI version = IRI.create("http://example.org/wp-test/shared/1");
        Session sameVersion = Session.open();
        assertThatThrownBy(() -> sameVersion.pipeline.apply(
                        USER,
                        new InitialImportGenerator(List.of(
                                ontology(IRI.create("http://example.org/wp-test/left"), version),
                                ontology(IRI.create("http://example.org/wp-test/right"), version)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(version.toString());
        assertUnchanged(sameVersion);
    }

    @Test
    void copies_a_version_iri_and_distinct_document_iris() {
        IRI ontologyIri = IRI.create("http://example.org/wp-test/series");
        IRI firstVersion = IRI.create("http://example.org/wp-test/series/1");
        IRI secondVersion = IRI.create("http://example.org/wp-test/series/2");
        List<OWLOntology> sources = List.of(
                ontology(ontologyIri, firstVersion), ontology(ontologyIri, secondVersion), anonymous(), anonymous());
        Session session = Session.open();

        ChangeApplicationResult<List<OntologyKey>> imported =
                session.pipeline.apply(USER, new InitialImportGenerator(sources));

        assertThat(imported.result())
                .containsExactly(OntologyKey.of(1), OntologyKey.of(2), OntologyKey.of(3), OntologyKey.of(4));
        assertThat(imported.revision().orElseThrow().description()).isEqualTo("Imported 4 ontologies");
        assertThat(session.runtime.ontologies().info(OntologyKey.of(1)))
                .contains(new OntologyInfo(OntologyKey.of(1), Optional.of(ontologyIri), Optional.of(firstVersion)));
        assertThat(session.runtime.ontologies().info(OntologyKey.of(2)))
                .contains(new OntologyInfo(OntologyKey.of(2), Optional.of(ontologyIri), Optional.of(secondVersion)));
        assertThat(session.runtime.ontologies().info(OntologyKey.of(3)))
                .contains(new OntologyInfo(OntologyKey.of(3), Optional.empty(), Optional.empty()));
        assertThat(session.runtime.ontologies().info(OntologyKey.of(4)))
                .contains(new OntologyInfo(OntologyKey.of(4), Optional.empty(), Optional.empty()));
    }

    @Test
    void rejects_a_null_ontology_list() {
        assertThatThrownBy(() -> new InitialImportGenerator(null)).isInstanceOf(NullPointerException.class);
        List<OWLOntology> sources = new ArrayList<>();
        sources.add(null);
        assertThatThrownBy(() -> new InitialImportGenerator(sources)).isInstanceOf(NullPointerException.class);
    }

    private static void assertGrouped(List<OntologyChange> changes, List<OWLOntology> sources) {
        int cursor = 0;
        for (int index = 0; index < sources.size(); index++) {
            OntologyKey key = OntologyKey.of(index + 1);
            OWLOntology source = sources.get(index);
            OWLOntologyID id = source.getOntologyID();
            assertThat(changes.get(cursor))
                    .isEqualTo(new DeclareOntology(key, id.getOntologyIRI(), id.getVersionIRI()));
            cursor++;
            cursor = takeAxioms(changes, cursor, key, source);
            cursor = takeAnnotations(changes, cursor, key, source);
            cursor = takeImports(changes, cursor, key, source);
        }
        assertThat(cursor).isEqualTo(changes.size());
    }

    private static int takeAxioms(List<OntologyChange> changes, int cursor, OntologyKey key, OWLOntology source) {
        List<OWLAxiom> axioms = new ArrayList<>();
        while (cursor < changes.size()
                && changes.get(cursor) instanceof AddAxiom add
                && add.ontology().equals(key)) {
            axioms.add(add.owlAxiom());
            cursor++;
        }
        assertThat(axioms).containsExactlyInAnyOrderElementsOf(source.getAxioms());
        return cursor;
    }

    private static int takeAnnotations(List<OntologyChange> changes, int cursor, OntologyKey key, OWLOntology source) {
        List<OWLAnnotation> annotations = new ArrayList<>();
        while (cursor < changes.size()
                && changes.get(cursor) instanceof AddOntologyAnnotation add
                && add.ontology().equals(key)) {
            annotations.add(add.annotation());
            cursor++;
        }
        assertThat(annotations).containsExactlyInAnyOrderElementsOf(source.getAnnotations());
        return cursor;
    }

    private static int takeImports(List<OntologyChange> changes, int cursor, OntologyKey key, OWLOntology source) {
        List<IRI> imports = new ArrayList<>();
        while (cursor < changes.size()
                && changes.get(cursor) instanceof AddImport add
                && add.ontology().equals(key)) {
            imports.add(add.iri());
            cursor++;
        }
        assertThat(imports).containsExactlyInAnyOrderElementsOf(importIris(source));
        return cursor;
    }

    private static void assertApplied(ProjectOntologies ontologies, List<OWLOntology> sources) {
        assertThat(ontologies.keys())
                .containsExactlyInAnyOrder(OntologyKey.of(1), OntologyKey.of(2), OntologyKey.of(3));
        long expectedAxioms = 0;
        for (int index = 0; index < sources.size(); index++) {
            OntologyKey key = OntologyKey.of(index + 1);
            OWLOntology source = sources.get(index);
            OWLOntologyID id = source.getOntologyID();
            expectedAxioms += source.getAxiomCount();
            assertThat(ontologies.info(key)).contains(new OntologyInfo(key, id.getOntologyIRI(), id.getVersionIRI()));
            assertThat(ontologies.axioms(key).toList()).containsExactlyInAnyOrderElementsOf(source.getAxioms());
            assertThat(ontologies.annotations(key)).containsExactlyInAnyOrderElementsOf(source.getAnnotations());
            assertThat(ontologies.imports(key)).containsExactlyInAnyOrderElementsOf(importIris(source));
        }
        assertThat(ontologies.axiomCount()).isEqualTo(expectedAxioms);
        assertThat(ontologies.imports(OntologyKey.of(1))).isEmpty();
        assertThat(ontologies.imports(OntologyKey.of(2))).isEmpty();
        assertThat(ontologies.imports(OntologyKey.of(3)))
                .containsExactlyInAnyOrder(
                        IRI.create("http://example.org/wp-test/imports/a"),
                        IRI.create("http://example.org/wp-test/imports/b"));
        assertThat(ontologies.axioms(OntologyKey.of(3)).toList())
                .noneMatch(axiom -> axiom.containsEntityInSignature(COMPONENT_A));
    }

    private static List<IRI> importIris(OWLOntology source) {
        return source.getImportsDeclarations().stream()
                .map(OWLImportsDeclaration::getIRI)
                .toList();
    }

    private static void assertUnchanged(Session session) {
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.ZERO);
        assertThat(session.runtime.ontologies().keys()).isEmpty();
        assertThat(session.log.get(session.projectId, RevisionNumber.of(1))).isEmpty();
    }

    private static OWLOntology ontology(IRI ontologyIri, IRI versionIri) {
        try {
            return OWLManager.createOWLOntologyManager()
                    .createOntology(new OWLOntologyID(Optional.of(ontologyIri), Optional.ofNullable(versionIri)));
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalStateException("failed to build a test ontology", ex);
        }
    }

    private static OWLOntology anonymous() {
        try {
            return OWLManager.createOWLOntologyManager().createOntology();
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalStateException("failed to build an anonymous ontology", ex);
        }
    }

    private static List<OWLOntology> load(List<String> files) {
        List<OWLOntology> sources = new ArrayList<>();
        for (String file : files) {
            sources.add(TestOntologies.load(file));
        }
        return sources;
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
    }
}
