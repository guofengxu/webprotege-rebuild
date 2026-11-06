package wprebuild.kernel.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.testsupport.TestOntologies;

/** Fast-path functional-syntax productions used by ofn-lines/1. */
class OfnExpressionParserTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey KEY = OntologyKey.of(1);

    private static final String AXIOM = "SubClassOf(<http://example.org/A> <http://example.org/B>)";

    private static final String COMMENT = "Annotation(<http://www.w3.org/2000/01/rdf-schema#comment> \"a\")";

    @Test
    void fast_path_parses_every_corpus_axiom_like_the_staging_parse() {
        OfnExpressionParser parser = parser();
        OWLOntology ontology = TestOntologies.load("axioms-all-types.ofn");

        assertThat(ontology.getAxioms()).isNotEmpty();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            String ofn = ofn(new AddAxiom(KEY, axiom));
            assertThat(parser.axiom(ofn)).contains(slowAxiom(ofn));
        }
    }

    @Test
    void fast_path_parses_ontology_annotations_like_the_staging_parse() {
        OfnExpressionParser parser = parser();
        OWLAnnotation plain = DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("hidden"));
        OWLAnnotation labelled = DATA.getOWLAnnotation(DATA.getRDFSLabel(), DATA.getOWLLiteral("人", "zh"));
        OWLAnnotation nested = DATA.getOWLAnnotation(
                DATA.getRDFSComment(),
                DATA.getOWLLiteral("inner"),
                Set.of(DATA.getOWLAnnotation(DATA.getRDFSLabel(), DATA.getOWLLiteral("meta"))));

        for (OWLAnnotation annotation : List.of(plain, labelled, nested)) {
            String ofn = ofn(new AddOntologyAnnotation(KEY, annotation));
            assertThat(parser.annotation(ofn)).contains(slowAnnotation(ofn));
        }
    }

    @Test
    void fast_path_declines_every_expression_the_staging_parse_rejects() {
        OfnExpressionParser parser = parser();
        List<String> rejected = List.of(
                "Hello",
                "Annotation(<http://www.w3.org/2000/01/rdf-schema#comment> \"hidden\") " + AXIOM,
                "Import(<file:///tmp/wp-missing.ofn>)",
                "Import(<file:///tmp/wp-missing.ofn>) " + AXIOM,
                AXIOM + " SubClassOf(<http://example.org/A> <http://example.org/C>)",
                AXIOM + " # comment",
                AXIOM + ")",
                "SubClassOf(<http://example.org/A>",
                "Prefix(ex:=<http://example.org/>)");

        for (String ofn : rejected) {
            assertThat(parser.axiom(ofn)).isEmpty();
            assertThatThrownBy(() -> new OfnLineDecoder(DATA).decode("+\tAXIOM\to1\t" + ofn))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(parser.annotation(COMMENT + " " + COMMENT)).isEmpty();
        assertThat(parser.annotation(AXIOM)).isEmpty();
    }

    @Test
    void fast_path_keeps_anonymous_individual_node_ids() {
        OfnExpressionParser parser = parser();

        OWLClassAssertionAxiom classAssertion = (OWLClassAssertionAxiom)
                parser.axiom("ClassAssertion(<http://example.org/A> _:b1)").orElseThrow();
        OWLObjectPropertyAssertionAxiom propertyAssertion = (OWLObjectPropertyAssertionAxiom)
                parser.axiom("ObjectPropertyAssertion(<http://example.org/knows> _:b1 _:b2)")
                        .orElseThrow();

        assertThat(classAssertion.getIndividual()).isEqualTo(DATA.getOWLAnonymousIndividual("b1"));
        assertThat(propertyAssertion.getSubject()).isEqualTo(DATA.getOWLAnonymousIndividual("b1"));
        assertThat(propertyAssertion.getObject()).isEqualTo(DATA.getOWLAnonymousIndividual("b2"));
    }

    @Test
    void fast_path_recovers_after_a_declined_expression() {
        OfnExpressionParser parser = parser();

        assertThat(parser.axiom("SubClassOf(<http://example.org/A>")).isEmpty();
        assertThat(parser.axiom(AXIOM)).contains(slowAxiom(AXIOM));
    }

    private static String ofn(OntologyChange change) {
        return OfnLines.encodeLine(change).split("\t", 4)[3];
    }

    private static OWLAxiom slowAxiom(String ofn) {
        return OfnLines.oneAxiom(new OfnLineDecoder(DATA).parse(ofn), ofn);
    }

    private static OWLAnnotation slowAnnotation(String ofn) {
        return OfnLines.oneAnnotation(new OfnLineDecoder(DATA).parse(ofn), ofn);
    }

    private static OfnExpressionParser parser() {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            manager.getOntologyConfigurator().withRemapAllAnonymousIndividualsIds(false);
            OWLOntology context = manager.createOntology();
            return new OfnExpressionParser(context, new OWLOntologyLoaderConfiguration());
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalStateException("failed to create ofn expression context", ex);
        }
    }
}
