package wprebuild.kernel.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.IriReplacer;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.DerivedView;
import wprebuild.kernel.runtime.FreshIriMinter;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;

/**
 * BS-13 merge-entities generator.
 *
 * <p>Ports assertions from legacy {@code MergeEntitiesChangeListGenerator_TestCase} and covers the
 * new-system validation, label-preservation fix, and deprecate declaration restore.
 */
class BS13_MergeEntitiesGeneratorTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-04T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final OntologyKey OTHER = OntologyKey.of(2);

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OWLClass SOURCE = DATA.getOWLClass(IRI.create("http://ontology.org/A"));

    private static final OWLClass TARGET = DATA.getOWLClass(IRI.create("http://ontology.org/B"));

    private static final OWLClass PARENT = DATA.getOWLClass(IRI.create("http://ontology.org/C"));

    private static final OWLClass CHILD = DATA.getOWLClass(IRI.create("http://ontology.org/D"));

    private static final OWLClass SOURCE_TWO = DATA.getOWLClass(IRI.create("http://ontology.org/E"));

    private static final OWLAnnotationProperty RDFS_LABEL = DATA.getRDFSLabel();

    private static final OWLAnnotationProperty SKOS_PREF =
            DATA.getOWLAnnotationProperty(SKOSVocabulary.PREFLABEL.getIRI());

    private static final OWLAnnotationProperty SKOS_ALT =
            DATA.getOWLAnnotationProperty(SKOSVocabulary.ALTLABEL.getIRI());

    private static final OWLAnnotationProperty RDFS_COMMENT = DATA.getRDFSComment();

    private static final OWLLiteral HELLO = DATA.getOWLLiteral("Hello", "en");

    private static final OWLLiteral BONJOUR = DATA.getOWLLiteral("Bonjour", "fr");

    private static final OWLLiteral HI = DATA.getOWLLiteral("hi", "en");

    @Test
    void replaces_source_with_target_on_delete() {
        Session session = seededLegacyFixture();

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "The commit message");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsEntity(SOURCE)).isFalse();
        assertThat(after.containsAxiom(ROOT, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT)))
                .isFalse();
        assertThat(after.containsAxiom(ROOT, DATA.getOWLSubClassOfAxiom(TARGET, PARENT)))
                .isTrue();
        assertThat(after.containsAxiom(ROOT, comment(SOURCE.getIRI(), HI))).isFalse();
        assertThat(after.containsAxiom(ROOT, comment(TARGET.getIRI(), HI))).isTrue();
    }

    @Test
    void demotes_rdfs_label_to_skos_alt_label_on_delete() {
        Session session = seededLegacyFixture();

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsAxiom(ROOT, label(SOURCE.getIRI(), HELLO))).isFalse();
        assertThat(after.containsAxiom(ROOT, label(TARGET.getIRI(), HELLO))).isFalse();
        assertThat(after.containsAxiom(ROOT, altLabel(TARGET.getIRI(), HELLO))).isTrue();
    }

    @Test
    void demotes_skos_pref_label_to_skos_alt_label_on_delete() {
        Session session = seededLegacyFixture();

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsAxiom(ROOT, prefLabel(SOURCE.getIRI(), BONJOUR)))
                .isFalse();
        assertThat(after.containsAxiom(ROOT, prefLabel(TARGET.getIRI(), BONJOUR)))
                .isFalse();
        assertThat(after.containsAxiom(ROOT, altLabel(TARGET.getIRI(), BONJOUR)))
                .isTrue();
    }

    @Test
    void delete_treatment_does_not_add_deprecation() {
        Session session = seededLegacyFixture();

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        assertThat(session.runtime.ontologies().containsAxiom(ROOT, deprecated(SOURCE.getIRI())))
                .isFalse();
    }

    @Test
    void deprecate_treatment_adds_deprecation() {
        Session session = seededLegacyFixture();

        session.merge(MergedEntityTreatment.DEPRECATE_MERGED_ENTITY, "msg");

        assertThat(session.runtime.ontologies().containsAxiom(ROOT, deprecated(SOURCE.getIRI())))
                .isTrue();
    }

    @Test
    void preserves_language_tags_and_nested_annotations_on_labels() {
        OWLAnnotation onAnnotation = DATA.getOWLAnnotation(RDFS_COMMENT, DATA.getOWLLiteral("note"));
        OWLAnnotation onAxiom = DATA.getOWLAnnotation(RDFS_COMMENT, DATA.getOWLLiteral("axiom-note"));
        OWLAnnotation labeled = DATA.getOWLAnnotation(RDFS_LABEL, HELLO, Set.of(onAnnotation));
        OWLAnnotationAssertionAxiom sourceLabel =
                DATA.getOWLAnnotationAssertionAxiom(SOURCE.getIRI(), labeled, Set.of(onAxiom));
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, sourceLabel)),
                "Seeded annotated label");

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        OWLAnnotation alt = DATA.getOWLAnnotation(SKOS_ALT, HELLO, Set.of(onAnnotation));
        OWLAnnotationAssertionAxiom expected =
                DATA.getOWLAnnotationAssertionAxiom(TARGET.getIRI(), alt, Set.of(onAxiom));
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, expected)).isTrue();
    }

    @Test
    void rewrites_source_in_each_ontology_separately() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new DeclareOntology(OTHER, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT)),
                        new AddAxiom(OTHER, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(OTHER, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT))),
                "Seeded two ontologies");

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsAxiom(ROOT, DATA.getOWLSubClassOfAxiom(TARGET, PARENT)))
                .isTrue();
        assertThat(after.containsAxiom(OTHER, DATA.getOWLSubClassOfAxiom(TARGET, PARENT)))
                .isTrue();
        assertThat(after.containsAxiom(ROOT, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT)))
                .isFalse();
        assertThat(after.containsAxiom(OTHER, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT)))
                .isFalse();
    }

    @Test
    void rewrites_annotation_assertion_whose_value_is_the_source_iri() {
        OWLAxiom seeAlso = DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSSeeAlso(), PARENT.getIRI(), SOURCE.getIRI());
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PARENT)),
                        new AddAxiom(ROOT, seeAlso)),
                "Seeded seeAlso");

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        OWLAxiom rewritten =
                DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSSeeAlso(), PARENT.getIRI(), TARGET.getIRI());
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, seeAlso)).isFalse();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, rewritten)).isTrue();
    }

    @Test
    void rewrites_annotation_property_domain_and_range_iris() {
        OWLAxiom domain = DATA.getOWLAnnotationPropertyDomainAxiom(RDFS_COMMENT, SOURCE.getIRI());
        OWLAxiom range = DATA.getOWLAnnotationPropertyRangeAxiom(DATA.getRDFSSeeAlso(), SOURCE.getIRI());
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, domain),
                        new AddAxiom(ROOT, range)),
                "Seeded domain and range");

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsAxiom(ROOT, domain)).isFalse();
        assertThat(after.containsAxiom(ROOT, range)).isFalse();
        assertThat(after.containsAxiom(ROOT, DATA.getOWLAnnotationPropertyDomainAxiom(RDFS_COMMENT, TARGET.getIRI())))
                .isTrue();
        assertThat(after.containsAxiom(
                        ROOT, DATA.getOWLAnnotationPropertyRangeAxiom(DATA.getRDFSSeeAlso(), TARGET.getIRI())))
                .isTrue();
    }

    @Test
    void rewrites_source_iri_in_axiom_annotations_on_logical_axioms() {
        OWLAnnotation seeAlso = DATA.getOWLAnnotation(DATA.getRDFSSeeAlso(), SOURCE.getIRI());
        OWLAxiom subclass = DATA.getOWLSubClassOfAxiom(CHILD, PARENT, Set.of(seeAlso));
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(CHILD)),
                        new AddAxiom(ROOT, subclass)),
                "Seeded axiom annotation");

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        OWLAxiom rewritten = DATA.getOWLSubClassOfAxiom(
                CHILD, PARENT, Set.of(DATA.getOWLAnnotation(DATA.getRDFSSeeAlso(), TARGET.getIRI())));
        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsAxiom(ROOT, subclass)).isFalse();
        assertThat(after.containsAxiom(ROOT, rewritten)).isTrue();
    }

    @Test
    void deprecate_keeps_source_annotations_and_contains_entity() {
        Session session = seededLegacyFixture();

        session.merge(MergedEntityTreatment.DEPRECATE_MERGED_ENTITY, "msg");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsEntity(SOURCE)).isTrue();
        assertThat(after.containsAxiom(ROOT, label(SOURCE.getIRI(), HELLO))).isTrue();
        assertThat(after.containsAxiom(ROOT, prefLabel(SOURCE.getIRI(), BONJOUR)))
                .isTrue();
        assertThat(after.containsAxiom(ROOT, comment(SOURCE.getIRI(), HI))).isTrue();
        assertThat(after.containsAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)))
                .isTrue();
    }

    @Test
    void keeps_identical_target_rdfs_label_when_demoting_source_label() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, label(SOURCE.getIRI(), HELLO)),
                        new AddAxiom(ROOT, label(TARGET.getIRI(), HELLO))),
                "Seeded shared label");

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsAxiom(ROOT, label(TARGET.getIRI(), HELLO))).isTrue();
        assertThat(after.containsAxiom(ROOT, altLabel(TARGET.getIRI(), HELLO))).isTrue();
    }

    @Test
    void merges_two_source_entities_into_the_target() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE_TWO)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(SOURCE_TWO, PARENT)),
                        new AddAxiom(ROOT, label(SOURCE.getIRI(), HELLO)),
                        new AddAxiom(ROOT, label(SOURCE_TWO.getIRI(), BONJOUR))),
                "Seeded two sources");

        ChangeApplicationResult<OWLEntity> merged = session.pipeline.apply(
                USER,
                new MergeEntitiesGenerator(
                        Set.of(SOURCE.getIRI(), SOURCE_TWO.getIRI()),
                        TARGET.getIRI(),
                        MergedEntityTreatment.DELETE_MERGED_ENTITY,
                        ""));

        assertThat(merged.result()).isEqualTo(TARGET);
        assertThat(merged.revision().orElseThrow().description()).isEqualTo("Merged 2 entities into B");
        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsEntity(SOURCE)).isFalse();
        assertThat(after.containsEntity(SOURCE_TWO)).isFalse();
        assertThat(after.containsAxiom(ROOT, altLabel(TARGET.getIRI(), HELLO))).isTrue();
        assertThat(after.containsAxiom(ROOT, altLabel(TARGET.getIRI(), BONJOUR)))
                .isTrue();
    }

    @Test
    void rejects_empty_sources_before_any_change() {
        Session session = Session.open();
        session.apply(List.of(new DeclareOntology(ROOT, Optional.empty(), Optional.empty())), "Declared");

        assertRejected(session, Set.of(), TARGET.getIRI(), MergeEntitiesException.Reason.EMPTY_SOURCES);
    }

    @Test
    void rejects_target_in_sources() {
        Session session = seededDeclarations(SOURCE, TARGET);

        assertRejected(
                session,
                Set.of(SOURCE.getIRI(), TARGET.getIRI()),
                TARGET.getIRI(),
                MergeEntitiesException.Reason.TARGET_IN_SOURCES);
    }

    @Test
    void rejects_undeclared_built_in_thing_as_built_in_not_unknown() {
        Session session = seededDeclarations(TARGET);

        assertRejected(
                session,
                Set.of(DATA.getOWLThing().getIRI()),
                TARGET.getIRI(),
                MergeEntitiesException.Reason.BUILT_IN_ENTITY);
    }

    @Test
    void rejects_unknown_entity() {
        OWLClass missing = DATA.getOWLClass(IRI.create("http://ontology.org/Missing"));
        Session session = seededDeclarations(TARGET);

        assertRejected(
                session, Set.of(missing.getIRI()), TARGET.getIRI(), MergeEntitiesException.Reason.UNKNOWN_ENTITY);
    }

    @Test
    void rejects_type_mismatch() {
        OWLNamedIndividual individual = DATA.getOWLNamedIndividual(IRI.create("http://ontology.org/Ind"));
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(individual)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET))),
                "Seeded mismatch");

        assertRejected(
                session, Set.of(individual.getIRI()), TARGET.getIRI(), MergeEntitiesException.Reason.TYPE_MISMATCH);
    }

    @Test
    void rejects_target_declared_as_multiple_types() {
        OWLNamedIndividual targetAsIndividual = DATA.getOWLNamedIndividual(TARGET.getIRI());
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(targetAsIndividual))),
                "Seeded punned target");

        assertRejected(session, Set.of(SOURCE.getIRI()), TARGET.getIRI(), MergeEntitiesException.Reason.TYPE_MISMATCH);
    }

    @Test
    void keeps_identical_target_skos_pref_label_when_demoting_source_label() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, prefLabel(SOURCE.getIRI(), BONJOUR)),
                        new AddAxiom(ROOT, prefLabel(TARGET.getIRI(), BONJOUR))),
                "Seeded shared prefLabel");

        session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        ProjectOntologies after = session.runtime.ontologies();
        assertThat(after.containsAxiom(ROOT, prefLabel(TARGET.getIRI(), BONJOUR)))
                .isTrue();
        assertThat(after.containsAxiom(ROOT, altLabel(TARGET.getIRI(), BONJOUR)))
                .isTrue();
    }

    @Test
    void pipeline_delete_updates_hierarchy_and_revert_restores() {
        Session session = Session.openWithHierarchy();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(CHILD)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(TARGET, PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(CHILD, SOURCE))),
                "Seeded hierarchy");
        assertThat(session.runtime.view(ClassHierarchyView.class).children(SOURCE))
                .contains(CHILD);

        ChangeApplicationResult<OWLEntity> merged =
                session.merge(MergedEntityTreatment.DELETE_MERGED_ENTITY, "Merged A into B");
        RevisionNumber mergeRevision = merged.revision().orElseThrow().number();
        assertThat(session.runtime.ontologies().containsEntity(SOURCE)).isFalse();
        assertThat(session.runtime.view(ClassHierarchyView.class).children(TARGET))
                .contains(CHILD);

        ChangeApplicationResult<RevertResult> reverted = session.pipeline.apply(
                USER, new RevertRevisionGenerator(session.projectId, mergeRevision, session.log));

        assertThat(reverted.result().reverted()).isTrue();
        assertThat(session.runtime.ontologies().containsEntity(SOURCE)).isTrue();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, DATA.getOWLSubClassOfAxiom(CHILD, SOURCE)))
                .isTrue();
        assertThat(session.runtime.view(ClassHierarchyView.class).children(SOURCE))
                .contains(CHILD);
    }

    @Test
    void rename_result_keeps_the_target_entity() {
        MergeEntitiesGenerator generator = new MergeEntitiesGenerator(
                Set.of(SOURCE.getIRI()), TARGET.getIRI(), MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");

        assertThat(generator.renameResult(TARGET, Map.of(SOURCE.getIRI(), TARGET.getIRI())))
                .isSameAs(TARGET);
    }

    @Test
    void iri_replacer_rewrites_annotation_value_iris() {
        OWLAxiom seeAlso = DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSSeeAlso(), PARENT.getIRI(), SOURCE.getIRI());

        OWLAxiom rewritten = IriReplacer.replaceObject(seeAlso, Map.of(SOURCE.getIRI(), TARGET.getIRI()));

        assertThat(rewritten)
                .isEqualTo(
                        DATA.getOWLAnnotationAssertionAxiom(DATA.getRDFSSeeAlso(), PARENT.getIRI(), TARGET.getIRI()));
    }

    private static Session seededLegacyFixture() {
        Session session = Session.open();
        session.apply(
                List.of(
                        new DeclareOntology(ROOT, Optional.empty(), Optional.empty()),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(SOURCE)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(TARGET)),
                        new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(SOURCE, PARENT)),
                        new AddAxiom(ROOT, DATA.getOWLSubClassOfAxiom(TARGET, PARENT)),
                        new AddAxiom(ROOT, label(SOURCE.getIRI(), HELLO)),
                        new AddAxiom(ROOT, prefLabel(SOURCE.getIRI(), BONJOUR)),
                        new AddAxiom(ROOT, comment(SOURCE.getIRI(), HI))),
                "Seeded legacy fixture");
        return session;
    }

    private static Session seededDeclarations(OWLEntity... entities) {
        Session session = Session.open();
        List<OntologyChange> changes = new ArrayList<>();
        changes.add(new DeclareOntology(ROOT, Optional.empty(), Optional.empty()));
        for (OWLEntity entity : entities) {
            changes.add(new AddAxiom(ROOT, DATA.getOWLDeclarationAxiom(entity)));
        }
        session.apply(changes, "Seeded declarations");
        return session;
    }

    private static void assertRejected(
            Session session, Set<IRI> sources, IRI target, MergeEntitiesException.Reason reason) {
        MergeEntitiesGenerator generator =
                new MergeEntitiesGenerator(sources, target, MergedEntityTreatment.DELETE_MERGED_ENTITY, "msg");
        RevisionNumber before = session.runtime.head();
        assertThatThrownBy(() -> session.pipeline.apply(USER, generator))
                .isInstanceOf(MergeEntitiesException.class)
                .extracting(ex -> ((MergeEntitiesException) ex).reason())
                .isEqualTo(reason);
        assertThat(session.runtime.head()).isEqualTo(before);
    }

    private static OWLAnnotationAssertionAxiom label(IRI subject, OWLLiteral value) {
        return DATA.getOWLAnnotationAssertionAxiom(RDFS_LABEL, subject, value);
    }

    private static OWLAnnotationAssertionAxiom prefLabel(IRI subject, OWLLiteral value) {
        return DATA.getOWLAnnotationAssertionAxiom(SKOS_PREF, subject, value);
    }

    private static OWLAnnotationAssertionAxiom altLabel(IRI subject, OWLLiteral value) {
        return DATA.getOWLAnnotationAssertionAxiom(SKOS_ALT, subject, value);
    }

    private static OWLAnnotationAssertionAxiom comment(IRI subject, OWLLiteral value) {
        return DATA.getOWLAnnotationAssertionAxiom(RDFS_COMMENT, subject, value);
    }

    private static OWLAnnotationAssertionAxiom deprecated(IRI subject) {
        return DATA.getDeprecatedOWLAnnotationAssertionAxiom(subject);
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
            return open(List.of());
        }

        private static Session openWithHierarchy() {
            return open(List.of(ClassHierarchyView::new));
        }

        private static Session open(List<Supplier<DerivedView>> views) {
            ProjectId projectId = ProjectId.random();
            InMemoryRevisionLog log = new InMemoryRevisionLog();
            ProjectRuntime runtime = new ProjectRuntimeLoader(
                            log, new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), views)
                    .load(projectId);
            ChangePipeline pipeline =
                    new ChangePipeline(runtime, new ProjectWriteGate(), log, FreshIriMinter.NONE, List.of(), CLOCK);
            return new Session(projectId, log, runtime, pipeline);
        }

        private ChangeApplicationResult<OWLEntity> merge(MergedEntityTreatment treatment, String description) {
            return pipeline.apply(
                    USER, new MergeEntitiesGenerator(Set.of(SOURCE.getIRI()), TARGET.getIRI(), treatment, description));
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
