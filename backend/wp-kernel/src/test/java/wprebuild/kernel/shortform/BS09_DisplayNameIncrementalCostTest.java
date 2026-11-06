package wprebuild.kernel.shortform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.testsupport.CountingProjectOntologies;

/**
 * BS-09. One label edit recomputes that entity only, and does not materialise the project signature
 * or scan axioms by type (P1-25).
 */
class BS09_DisplayNameIncrementalCostTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private static final IRI RDFS_LABEL = OWLRDFVocabulary.RDFS_LABEL.getIRI();

    private static final List<DisplayNameSetting> SETTINGS = List.of(
            new DisplayNameSetting(RDFS_LABEL, "zh"),
            new DisplayNameSetting(RDFS_LABEL, "en"),
            new DisplayNameSetting(SKOSVocabulary.PREFLABEL.getIRI(), ""));

    @Test
    void single_label_edit_does_not_scan_the_project_signature() {
        OWLClass tray = cls("Tray");
        OWLClass bin = cls("Bin");
        MutableProjectOntologies ontologies = declared();
        ontologies.apply(List.of(
                declaration(tray), declaration(bin), add(label(bin, "zh", "料箱")), add(label(tray, "en", "Tray"))));
        DisplayNameView view = new DisplayNameView(SETTINGS);
        view.rebuild(ontologies);

        AddAxiom change = add(label(tray, "zh", "托盘"));
        ontologies.apply(List.of(change));
        CountingProjectOntologies counted = new CountingProjectOntologies(ontologies);
        view.onApplied(List.of(change), counted);

        assertThat(counted.signatureCalls()).isZero();
        assertThat(counted.axiomsOfTypeCalls()).isZero();
        assertThat(view.lastChanged()).containsExactly(tray);
        assertThat(view.displayName(tray)).isEqualTo("托盘");
        assertThat(view.displayName(bin)).isEqualTo("料箱");
    }

    private static MutableProjectOntologies declared() {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(List.of(new DeclareOntology(ONTOLOGY, Optional.empty(), Optional.empty())));
        return ontologies;
    }

    private static AddAxiom declaration(OWLClass cls) {
        return new AddAxiom(ONTOLOGY, DATA.getOWLDeclarationAxiom(cls));
    }

    private static AddAxiom add(OWLAnnotationAssertionAxiom axiom) {
        return new AddAxiom(ONTOLOGY, axiom);
    }

    private static OWLAnnotationAssertionAxiom label(OWLClass entity, String lang, String text) {
        OWLAnnotationProperty property = DATA.getOWLAnnotationProperty(RDFS_LABEL);
        return DATA.getOWLAnnotationAssertionAxiom(property, entity.getIRI(), DATA.getOWLLiteral(text, lang));
    }

    private static OWLClass cls(String local) {
        return DATA.getOWLClass(IRI.create("http://example.org/" + local));
    }
}
