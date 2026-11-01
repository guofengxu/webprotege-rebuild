package wprebuild.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class OwlApiSmokeTest {

    private static final String TURTLE = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix ex: <http://example.org/smoke#> .

            ex:Person a owl:Class ;
                rdfs:subClassOf owl:Thing ;
                rdfs:label "人"@zh .
            """;

    @Test
    void round_trips_class_subclass_and_chinese_label_through_functional_syntax() throws Exception {
        OWLOntology loaded = parse(TURTLE);
        String functionalSyntax = writeFunctionalSyntax(loaded);
        OWLOntology reparsed = parse(functionalSyntax);

        Set<OWLAxiom> loadedAxioms = loaded.getAxioms();
        assertThat(reparsed.getAxioms()).isEqualTo(loadedAxioms);
        assertThat(loadedAxioms).anyMatch(axiom -> axiom.toString().contains("人"));
    }

    private static OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(new StringDocumentSource(document));
    }

    private static String writeFunctionalSyntax(OWLOntology ontology) throws Exception {
        StringDocumentTarget target = new StringDocumentTarget();
        ontology.getOWLOntologyManager()
                .saveOntology(ontology, new FunctionalSyntaxDocumentFormat(), target);
        return target.toString();
    }
}
