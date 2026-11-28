package wprebuild.kernel.form;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.FreshIriMinter;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;
import wprebuild.kernel.runtime.StaleHeadException;

/**
 * BS-18: entity form save → axiom changes (first slice: TEXT/NUMBER + PROPERTY).
 */
class BS18_EntityFormChangeListGeneratorTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-28T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OWLClass PERSON = DATA.getOWLClass(IRI.create("http://example.org/Person"));

    private static final OWLAnnotationProperty COMMENT = DATA.getRDFSComment();

    private static final OWLAnnotationProperty LABEL = DATA.getRDFSLabel();

    private static final FormId FORM_ID = new FormId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    private static final FormFieldId COMMENT_FIELD =
            new FormFieldId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    private static final FormFieldId NUMBER_FIELD =
            new FormFieldId(UUID.fromString("33333333-3333-3333-3333-333333333333"));

    private static final FormFieldId IMAGE_FIELD =
            new FormFieldId(UUID.fromString("44444444-4444-4444-4444-444444444444"));

    private static final OWLAnnotationProperty MASS =
            DATA.getOWLAnnotationProperty(IRI.create("http://example.org/mass"));

    @Test
    void writes_text_property_change_matching_direct_axiom_edit() {
        Session session = Session.open();
        seedPersonWithComment(session, "old");

        FormDescriptor descriptor = textDescriptor();
        FormData pristine = formData(descriptor, COMMENT_FIELD, literal("old"));
        FormData edited = formData(descriptor, COMMENT_FIELD, literal("new"));

        ChangeApplicationResult<OWLEntity> result = session.pipeline.apply(
                USER,
                new EntityFormChangeListGenerator(PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited), ROOT),
                RevisionNumber.of(1));

        assertThat(result.revision()).isPresent();
        assertThat(result.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(2));
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(2));
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, comment("old")))
                .isFalse();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, comment("new")))
                .isTrue();

        Session direct = Session.open();
        seedPersonWithComment(direct, "old");
        ChangeApplicationResult<String> directResult = direct.pipeline.apply(
                USER,
                fixed(List.of(new RemoveAxiom(ROOT, comment("old")), new AddAxiom(ROOT, comment("new"))), "direct"),
                RevisionNumber.of(1));
        assertThat(direct.runtime.ontologies().containsAxiom(ROOT, comment("new")))
                .isTrue();
        assertThat(axioms(result)).containsExactlyInAnyOrderElementsOf(axioms(directResult));
    }

    @Test
    void writes_number_property_change() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON)),
                        new AddAxiom(ROOT, mass("1.5"))),
                "seed");

        FormDescriptor descriptor = numberDescriptor();
        FormData pristine = formData(descriptor, NUMBER_FIELD, decimal("1.5"));
        FormData edited = formData(descriptor, NUMBER_FIELD, decimal("2.0"));

        ChangeApplicationResult<OWLEntity> result = session.pipeline.apply(
                USER,
                new EntityFormChangeListGenerator(PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited), ROOT),
                RevisionNumber.of(1));

        assertThat(result.revision()).isPresent();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, mass("1.5")))
                .isFalse();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, mass("2.0")))
                .isTrue();
    }

    @Test
    void rejects_when_pristine_does_not_match_runtime() {
        Session session = Session.open();
        seedPersonWithComment(session, "actual");

        FormDescriptor descriptor = textDescriptor();
        FormData pristine = formData(descriptor, COMMENT_FIELD, literal("stale-client"));
        FormData edited = formData(descriptor, COMMENT_FIELD, literal("new"));

        long axiomCount = session.runtime.ontologies().axiomCount();
        assertThatThrownBy(() -> session.pipeline.apply(
                        USER,
                        new EntityFormChangeListGenerator(
                                PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited), ROOT),
                        RevisionNumber.of(1)))
                .isInstanceOf(FormDataChangedException.class);

        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(session.runtime.ontologies().axiomCount()).isEqualTo(axiomCount);
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, comment("actual")))
                .isTrue();
        assertThat(session.log.get(session.projectId, RevisionNumber.of(2))).isEmpty();
    }

    @Test
    void rejects_stale_expected_head_without_committing() {
        Session session = Session.open();
        seedPersonWithComment(session, "old");

        FormDescriptor descriptor = textDescriptor();
        FormData pristine = formData(descriptor, COMMENT_FIELD, literal("old"));
        FormData edited = formData(descriptor, COMMENT_FIELD, literal("new"));

        assertThatThrownBy(() -> session.pipeline.apply(
                        USER,
                        new EntityFormChangeListGenerator(
                                PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited), ROOT),
                        RevisionNumber.ZERO))
                .isInstanceOf(StaleHeadException.class);

        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, comment("old")))
                .isTrue();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, comment("new")))
                .isFalse();
    }

    @Test
    void round_trip_without_edits_does_not_create_revision() {
        Session session = Session.open();
        seedPersonWithComment(session, "same");

        FormDescriptor descriptor = textDescriptor();
        FormData pristine = formData(descriptor, COMMENT_FIELD, literal("same"));
        FormData edited = formData(descriptor, COMMENT_FIELD, literal("same"));

        ChangeApplicationResult<OWLEntity> result = session.pipeline.apply(
                USER,
                new EntityFormChangeListGenerator(PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited), ROOT),
                RevisionNumber.of(1));

        assertThat(result.revision()).isEmpty();
        assertThat(result.effectiveChanges()).isEmpty();
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(session.log.get(session.projectId, RevisionNumber.of(2))).isEmpty();
    }

    @Test
    void anchors_pristine_ignoring_axiom_annotations_and_removes_annotated_stored_axiom() {
        Session session = Session.open();
        OWLAnnotationAssertionAxiom annotated = DATA.getOWLAnnotationAssertionAxiom(
                COMMENT, PERSON.getIRI(), literal("old"), Set.of(DATA.getRDFSLabel("meta")));
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON)),
                        new AddAxiom(ROOT, annotated)),
                "seed");

        FormDescriptor descriptor = textDescriptor();
        FormData pristine = formData(descriptor, COMMENT_FIELD, literal("old"));
        FormData edited = formData(descriptor, COMMENT_FIELD, literal("new"));

        ChangeApplicationResult<OWLEntity> result = session.pipeline.apply(
                USER,
                new EntityFormChangeListGenerator(PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited), ROOT),
                RevisionNumber.of(1));

        assertThat(result.revision()).isPresent();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, annotated)).isFalse();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, comment("new")))
                .isTrue();
    }

    @Test
    void rejects_object_property_text_edits_as_unsupported() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON))),
                "seed");

        var partOf = DATA.getOWLObjectProperty(IRI.create("http://example.org/partOf"));
        FormDescriptor descriptor = new FormDescriptor(
                FORM_ID,
                List.of(new FormFieldDescriptor(COMMENT_FIELD, FormControlType.TEXT, new OwlPropertyBinding(partOf))));
        FormData pristine = formData(descriptor, COMMENT_FIELD, literal("a"));
        FormData edited = formData(descriptor, COMMENT_FIELD, literal("b"));

        assertThatThrownBy(() -> session.pipeline.apply(
                        USER,
                        new EntityFormChangeListGenerator(
                                PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited), ROOT),
                        RevisionNumber.of(1)))
                .isInstanceOf(UnsupportedFormControlEditException.class);
    }

    @Test
    void rejects_mismatched_form_ids_or_descriptors() {
        FormDescriptor text = textDescriptor();
        FormDescriptor other = new FormDescriptor(
                FORM_ID,
                List.of(new FormFieldDescriptor(COMMENT_FIELD, FormControlType.TEXT, new OwlPropertyBinding(LABEL))));
        FormData pristine = formData(text, COMMENT_FIELD, literal("a"));
        FormData edited = formData(other, COMMENT_FIELD, literal("b"));

        assertThatThrownBy(() -> FormDataUpdateSanityChecker.check(Map.of(FORM_ID, pristine), Map.of(FORM_ID, edited)))
                .isInstanceOf(IllegalArgumentException.class);

        FormId otherId = new FormId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        assertThatThrownBy(() -> FormDataUpdateSanityChecker.check(
                        Map.of(FORM_ID, pristine), Map.of(otherId, formData(text, COMMENT_FIELD, literal("a")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_edits_to_unsupported_controls_but_ignores_equal_values() {
        Session session = Session.open();
        seedPersonWithComment(session, "keep");

        FormDescriptor descriptor = mixedDescriptor();
        FormData pristine = FormData.of(
                FORM_ID,
                descriptor,
                List.of(
                        new FormFieldValue(COMMENT_FIELD, Optional.of(literal("keep"))),
                        new FormFieldValue(IMAGE_FIELD, Optional.of(literal("http://example.org/a.png")))));
        FormData editedEqual = FormData.of(
                FORM_ID,
                descriptor,
                List.of(
                        new FormFieldValue(COMMENT_FIELD, Optional.of(literal("keep"))),
                        new FormFieldValue(IMAGE_FIELD, Optional.of(literal("http://example.org/a.png")))));

        ChangeApplicationResult<OWLEntity> noop = session.pipeline.apply(
                USER,
                new EntityFormChangeListGenerator(
                        PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, editedEqual), ROOT),
                RevisionNumber.of(1));
        assertThat(noop.revision()).isEmpty();

        FormData editedImage = FormData.of(
                FORM_ID,
                descriptor,
                List.of(
                        new FormFieldValue(COMMENT_FIELD, Optional.of(literal("keep"))),
                        new FormFieldValue(IMAGE_FIELD, Optional.of(literal("http://example.org/b.png")))));
        assertThatThrownBy(() -> session.pipeline.apply(
                        USER,
                        new EntityFormChangeListGenerator(
                                PERSON, Map.of(FORM_ID, pristine), Map.of(FORM_ID, editedImage), ROOT),
                        RevisionNumber.of(1)))
                .isInstanceOf(UnsupportedFormControlEditException.class);
    }

    private static void seedPersonWithComment(Session session, String value) {
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PERSON)),
                        new AddAxiom(ROOT, comment(value))),
                "seed");
    }

    private static FormDescriptor textDescriptor() {
        return new FormDescriptor(
                FORM_ID,
                List.of(new FormFieldDescriptor(COMMENT_FIELD, FormControlType.TEXT, new OwlPropertyBinding(COMMENT))));
    }

    private static FormDescriptor numberDescriptor() {
        return new FormDescriptor(
                FORM_ID,
                List.of(new FormFieldDescriptor(NUMBER_FIELD, FormControlType.NUMBER, new OwlPropertyBinding(MASS))));
    }

    private static FormDescriptor mixedDescriptor() {
        return new FormDescriptor(
                FORM_ID,
                List.of(
                        new FormFieldDescriptor(COMMENT_FIELD, FormControlType.TEXT, new OwlPropertyBinding(COMMENT)),
                        new FormFieldDescriptor(IMAGE_FIELD, FormControlType.OTHER, new OwlPropertyBinding(LABEL))));
    }

    private static FormData formData(FormDescriptor descriptor, FormFieldId fieldId, OWLLiteral value) {
        return FormData.of(FORM_ID, descriptor, List.of(new FormFieldValue(fieldId, Optional.of(value))));
    }

    private static OWLLiteral literal(String value) {
        return DATA.getOWLLiteral(value);
    }

    private static OWLLiteral decimal(String value) {
        return DATA.getOWLLiteral(value, DATA.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#decimal")));
    }

    private static OWLAnnotationAssertionAxiom comment(String value) {
        return DATA.getOWLAnnotationAssertionAxiom(COMMENT, PERSON.getIRI(), literal(value));
    }

    private static OWLAnnotationAssertionAxiom mass(String value) {
        return DATA.getOWLAnnotationAssertionAxiom(MASS, PERSON.getIRI(), decimal(value));
    }

    private static List<OWLAxiom> axioms(ChangeApplicationResult<?> result) {
        return result.effectiveChanges().stream()
                .flatMap(change -> change.axiom().stream())
                .toList();
    }

    private static ChangeListGenerator<String> fixed(List<OntologyChange> changes, String description) {
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

    private static final class Session {
        private final ProjectId projectId;
        private final ProjectRuntime runtime;
        private final InMemoryRevisionLog log;
        private final ChangePipeline pipeline;

        private Session(ProjectId projectId, ProjectRuntime runtime, InMemoryRevisionLog log, ChangePipeline pipeline) {
            this.projectId = projectId;
            this.runtime = runtime;
            this.log = log;
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
            return new Session(projectId, runtime, log, pipeline);
        }

        private void apply(List<OntologyChange> changes, String description) {
            pipeline.apply(USER, fixed(changes, description));
        }
    }
}
