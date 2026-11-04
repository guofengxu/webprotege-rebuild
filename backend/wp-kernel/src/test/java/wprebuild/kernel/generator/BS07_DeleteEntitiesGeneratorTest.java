package wprebuild.kernel.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
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

/**
 * BS-07 delete path, including IRI mentions that {@link ProjectOntologies#referencingAxioms} does
 * not return.
 *
 * <p>Old {@code EntityDeleter} removes annotation assertions whose value is the entity IRI, annotation
 * property domain and range IRIs, and ontology annotations that point at the entity
 * ({@code AnnotationAxiomsByIriReferenceIndexImpl}, {@code ReferenceFinder}). An annotation on a
 * logical axiom is not an IRI reference there, so that axiom stays.
 */
class BS07_DeleteEntitiesGeneratorTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-04T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final OntologyKey OTHER = OntologyKey.of(2);

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OWLClass EMPLOYEE = DATA.getOWLClass(IRI.create("http://example.org/Employee"));

    private static final OWLClass PERSON = DATA.getOWLClass(IRI.create("http://example.org/Person"));

    private static final OWLClass KEEPER = DATA.getOWLClass(IRI.create("http://example.org/Keeper"));

    @Test
    void deletes_signature_references_subject_annotations_and_iri_values() {
        OWLAxiom declaration = DATA.getOWLDeclarationAxiom(EMPLOYEE);
        OWLAxiom subclass = DATA.getOWLSubClassOfAxiom(EMPLOYEE, PERSON);
        OWLAnnotationAssertionAxiom label = DATA.getOWLAnnotationAssertionAxiom(
                DATA.getRDFSLabel(), EMPLOYEE.getIRI(), DATA.getOWLLiteral("Employee", "en"));
        OWLAxiom seeAlso =
                DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSSeeAlso(), PERSON.getIRI(), EMPLOYEE.getIRI());
        OWLAxiom domain = DATA.getOWLAnnotationPropertyDomainAxiom(DATA.getRDFSComment(), EMPLOYEE.getIRI());
        OWLAxiom range = DATA.getOWLAnnotationPropertyRangeAxiom(DATA.getRDFSIsDefinedBy(), EMPLOYEE.getIRI());
        OWLAxiom otherOntology = DATA.getOWLDeclarationAxiom(EMPLOYEE);
        OWLAxiom keptSubclass = DATA.getOWLSubClassOfAxiom(
                KEEPER, PERSON, List.of(DATA.getOWLAnnotation(DATA.getRDFSSeeAlso(), EMPLOYEE.getIRI())));
        OWLAxiom keptLabel =
                DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSLabel(), PERSON.getIRI(), DATA.getOWLLiteral("Person"));
        OWLAnnotation ontologyAnnotation = DATA.getOWLAnnotation(DATA.getRDFSSeeAlso(), EMPLOYEE.getIRI());
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new DeclareOntology(OTHER, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(KEEPER)),
                        new AddAxiom(ROOT, declaration),
                        new AddAxiom(ROOT, subclass),
                        new AddAxiom(ROOT, label),
                        new AddAxiom(ROOT, seeAlso),
                        new AddAxiom(ROOT, domain),
                        new AddAxiom(ROOT, range),
                        new AddAxiom(ROOT, keptSubclass),
                        new AddAxiom(ROOT, keptLabel),
                        new AddAxiom(OTHER, otherOntology),
                        new AddOntologyAnnotation(ROOT, ontologyAnnotation)),
                "Seeded Employee");
        ProjectOntologies before = session.runtime.ontologies();
        assertThat(before.referencingAxioms(EMPLOYEE).toList()).contains(declaration, subclass);
        assertThat(before.annotationAssertions(EMPLOYEE.getIRI()).toList()).contains(label);
        assertThat(before.containsAxiom(ROOT, seeAlso)).isTrue();

        ChangeApplicationResult<Set<OWLEntity>> deleted =
                session.pipeline.apply(USER, new DeleteEntitiesGenerator(Set.of(EMPLOYEE)));

        ProjectOntologies after = session.runtime.ontologies();
        Revision revision = deleted.revision().orElseThrow();
        assertThat(deleted.result()).containsExactly(EMPLOYEE);
        assertThat(revision.number()).isEqualTo(RevisionNumber.of(2));
        assertThat(revision.description()).isEqualTo("Deleted 1 entity");
        assertThat(after.referencingAxioms(EMPLOYEE).toList()).isEmpty();
        assertThat(after.annotationAssertions(EMPLOYEE.getIRI()).toList()).isEmpty();
        assertThat(after.containsAxiom(ROOT, seeAlso)).isFalse();
        assertThat(after.containsAxiom(ROOT, domain)).isFalse();
        assertThat(after.containsAxiom(ROOT, range)).isFalse();
        assertThat(after.containsAxiom(OTHER, otherOntology)).isFalse();
        assertThat(after.annotations(ROOT)).doesNotContain(ontologyAnnotation);
        assertThat(after.containsAxiom(ROOT, keptSubclass)).isTrue();
        assertThat(after.containsAxiom(ROOT, keptLabel)).isTrue();
        assertThat(after.containsAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON)))
                .isTrue();
    }

    @Test
    void describes_a_deletion_of_two_entities() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(EMPLOYEE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON))),
                "Seeded two classes");

        ChangeApplicationResult<Set<OWLEntity>> deleted =
                session.pipeline.apply(USER, new DeleteEntitiesGenerator(Set.of(EMPLOYEE, PERSON)));

        assertThat(deleted.revision().orElseThrow().description()).isEqualTo("Deleted 2 entities");
        assertThat(deleted.result()).containsExactlyInAnyOrder(EMPLOYEE, PERSON);
        assertThat(session.runtime.ontologies().referencingAxioms(EMPLOYEE).toList())
                .isEmpty();
        assertThat(session.runtime.ontologies().referencingAxioms(PERSON).toList())
                .isEmpty();
    }

    @Test
    void empty_entity_set_does_not_create_a_revision() {
        Session session = Session.open();
        session.apply(List.of(new DeclareOntology(ROOT, Optional.empty(), Optional.empty())), "Declared the ontology");

        ChangeApplicationResult<Set<OWLEntity>> deleted =
                session.pipeline.apply(USER, new DeleteEntitiesGenerator(Set.of()));

        assertThat(deleted.result()).isEmpty();
        assertThat(deleted.effectiveChanges()).isEmpty();
        assertThat(deleted.revision()).isEmpty();
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    void missing_entity_does_not_create_a_revision() {
        Session session = Session.open();
        session.apply(List.of(new DeclareOntology(ROOT, Optional.empty(), Optional.empty())), "Declared the ontology");

        ChangeApplicationResult<Set<OWLEntity>> deleted =
                session.pipeline.apply(USER, new DeleteEntitiesGenerator(Set.of(EMPLOYEE)));

        assertThat(deleted.result()).containsExactly(EMPLOYEE);
        assertThat(deleted.revision()).isEmpty();
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    void rejects_null_arguments() {
        assertThatThrownBy(() -> new DeleteEntitiesGenerator(null)).isInstanceOf(NullPointerException.class);
        DeleteEntitiesGenerator generator = new DeleteEntitiesGenerator(Set.of(EMPLOYEE));
        assertThatThrownBy(() -> generator.generate(null)).isInstanceOf(NullPointerException.class);
        GeneratedChanges<Set<OWLEntity>> generated = generator.generate(new ChangeContext(
                USER,
                new ProjectRuntimeLoader(
                                new InMemoryRevisionLog(),
                                new InMemorySnapshotStore(),
                                new OfnZipSnapshotCodec(),
                                List.of())
                        .load(ProjectId.random())
                        .ontologies()));
        assertThatThrownBy(() -> generator.describe(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.describe(generated, null)).isInstanceOf(NullPointerException.class);
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
            ChangePipeline pipeline =
                    new ChangePipeline(runtime, new ProjectWriteGate(), log, FreshIriMinter.NONE, List.of(), CLOCK);
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
