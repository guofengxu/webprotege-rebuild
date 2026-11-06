package wprebuild.kernel.shortform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * BS-09. Random label, declaration, subclass, punning, and ontology-annotation edits keep display
 * names equal to a full rebuild (P1-25).
 */
class BS09_DisplayNameIncrementalPropertyTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final IRI RDFS_LABEL = OWLRDFVocabulary.RDFS_LABEL.getIRI();

    private static final IRI SKOS_PREF_LABEL = SKOSVocabulary.PREFLABEL.getIRI();

    private static final IRI PUN = IRI.create("http://example.org/pool/A");

    private static final IRI OTHER = IRI.create("http://example.org/pool/B");

    private static final IRI THIRD = IRI.create("http://example.org/pool/C");

    private static final IRI UNDECLARED = IRI.create("http://example.org/pool/Undeclared");

    private static final List<IRI> SUBJECTS = List.of(PUN, OTHER, THIRD, UNDECLARED);

    private static final List<DisplayNameSetting> SETTINGS = List.of(
            new DisplayNameSetting(RDFS_LABEL, "zh"),
            new DisplayNameSetting(RDFS_LABEL, "en"),
            new DisplayNameSetting(SKOS_PREF_LABEL, ""));

    private static final List<OntologyChange> EDITS = edits();

    @Property(tries = 200)
    void step_by_step_updates_match_rebuild(@ForAll("sequences") List<OntologyChange> changes) {
        MutableProjectOntologies ontologies = emptyProject();
        DisplayNameView incremental = new DisplayNameView(SETTINGS);
        incremental.rebuild(ontologies);
        for (OntologyChange change : changes) {
            assertStep(incremental, ontologies, List.of(change));
        }
    }

    @Property(tries = 200)
    void one_batch_matches_rebuild(@ForAll("sequences") List<OntologyChange> changes) {
        MutableProjectOntologies ontologies = emptyProject();
        DisplayNameView incremental = new DisplayNameView(SETTINGS);
        incremental.rebuild(ontologies);
        assertStep(incremental, ontologies, changes);
    }

    @Provide
    Arbitrary<List<OntologyChange>> sequences() {
        return Arbitraries.of(EDITS.toArray(OntologyChange[]::new)).list().ofMaxSize(24);
    }

    private static void assertStep(
            DisplayNameView incremental, MutableProjectOntologies ontologies, List<OntologyChange> step) {
        DisplayNameView before = new DisplayNameView(SETTINGS);
        before.rebuild(ontologies);
        Set<OWLEntity> union = signature(ontologies);
        ontologies.apply(step);
        incremental.onApplied(step, ontologies);
        DisplayNameView rebuilt = new DisplayNameView(SETTINGS);
        rebuilt.rebuild(ontologies);
        union.addAll(signature(ontologies));
        assertThat(incremental.lastChanged()).isEqualTo(changedNames(before, rebuilt, union));
        assertMatchesRebuild(incremental, rebuilt, ontologies);
    }

    private static Set<OWLEntity> changedNames(DisplayNameView before, DisplayNameView after, Set<OWLEntity> union) {
        Set<OWLEntity> changed = new HashSet<>();
        for (OWLEntity entity : union) {
            if (!before.displayName(entity).equals(after.displayName(entity))) {
                changed.add(entity);
            }
        }
        return changed;
    }

    private static void assertMatchesRebuild(
            DisplayNameView incremental, DisplayNameView rebuilt, ProjectOntologies ontologies) {
        Set<IRI> iris = new HashSet<>(SUBJECTS);
        ontologies.signature().map(OWLEntity::getIRI).forEach(iris::add);
        for (IRI iri : iris) {
            for (EntityType<?> type : EntityType.values()) {
                OWLEntity entity = ontologies.dataFactory().getOWLEntity(type, iri);
                assertThat(incremental.displayName(entity)).isEqualTo(rebuilt.displayName(entity));
                assertThat(incremental.allNames(entity)).isEqualTo(rebuilt.allNames(entity));
            }
        }
    }

    private static Set<OWLEntity> signature(ProjectOntologies ontologies) {
        return ontologies.signature().collect(Collectors.toCollection(HashSet::new));
    }

    private static MutableProjectOntologies emptyProject() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(
                new DeclareOntology(FIRST, Optional.empty(), Optional.empty()),
                new DeclareOntology(SECOND, Optional.empty(), Optional.empty())));
        return ontologies;
    }

    private static List<OntologyChange> edits() {
        List<OntologyChange> changes = new ArrayList<>();
        List<IRI> properties = List.of(RDFS_LABEL, SKOS_PREF_LABEL);
        List<String> languages = List.of("", "zh", "en", "ZH");
        List<String> texts = List.of("alpha", "beta", "甲");
        for (OntologyKey key : List.of(FIRST, SECOND)) {
            for (IRI subject : SUBJECTS) {
                for (IRI property : properties) {
                    for (String lang : languages) {
                        for (String text : texts) {
                            addBoth(changes, key, literalAssertion(property, subject, lang, text));
                        }
                    }
                }
                addBoth(changes, key, iriValuedAssertion(subject));
            }
            for (OWLAxiom axiom : structuralAxioms()) {
                addBoth(changes, key, axiom);
            }
            for (OWLAnnotation annotation : ontologyAnnotations()) {
                changes.add(new AddOntologyAnnotation(key, annotation));
                changes.add(new RemoveOntologyAnnotation(key, annotation));
            }
            IRI imported = IRI.create("http://example.org/imports/other");
            changes.add(new AddImport(key, imported));
            changes.add(new RemoveImport(key, imported));
        }
        return List.copyOf(changes);
    }

    private static void addBoth(List<OntologyChange> changes, OntologyKey key, OWLAxiom axiom) {
        changes.add(new AddAxiom(key, axiom));
        changes.add(new RemoveAxiom(key, axiom));
    }

    private static List<OWLAxiom> structuralAxioms() {
        OWLClass pun = DATA.getOWLClass(PUN);
        OWLClass other = DATA.getOWLClass(OTHER);
        OWLClass third = DATA.getOWLClass(THIRD);
        OWLNamedIndividual punIndividual = DATA.getOWLNamedIndividual(PUN);
        return List.of(
                DATA.getOWLDeclarationAxiom(pun),
                DATA.getOWLDeclarationAxiom(other),
                DATA.getOWLDeclarationAxiom(third),
                DATA.getOWLDeclarationAxiom(punIndividual),
                DATA.getOWLSubClassOfAxiom(pun, other),
                DATA.getOWLSubClassOfAxiom(other, third),
                DATA.getOWLSubClassOfAxiom(third, pun));
    }

    private static List<OWLAnnotation> ontologyAnnotations() {
        return List.of(
                DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("note")),
                DATA.getOWLAnnotation(DATA.getRDFSLabel(), DATA.getOWLLiteral("本体", "zh")),
                DATA.getOWLAnnotation(
                        DATA.getOWLAnnotationProperty(IRI.create("http://example.org/pool/note")),
                        DATA.getOWLLiteral("x")),
                DATA.getOWLAnnotation(DATA.getRDFSSeeAlso(), PUN));
    }

    private static OWLAnnotationAssertionAxiom literalAssertion(IRI property, IRI subject, String lang, String text) {
        return DATA.getOWLAnnotationAssertionAxiom(
                DATA.getOWLAnnotationProperty(property),
                subject,
                lang.isEmpty() ? DATA.getOWLLiteral(text) : DATA.getOWLLiteral(text, lang));
    }

    private static OWLAnnotationAssertionAxiom iriValuedAssertion(IRI subject) {
        return DATA.getOWLAnnotationAssertionAxiom(
                DATA.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_SEE_ALSO.getIRI()),
                subject,
                IRI.create("http://example.org/pool/ref"));
    }
}
