package wprebuild.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.core.env.Environment;
import wprebuild.app.support.DatabaseTest;

@DatabaseTest
class WpApplicationContextTest {

    private static final String MINIMAL_TURTLE = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix ex: <http://example.org/smoke#> .

            ex:Person a owl:Class .
            """;

    private final Environment environment;

    WpApplicationContextTest(Environment environment) {
        this.environment = environment;
    }

    @Test
    void context_starts_and_owl_api_parses_a_minimal_ontology() throws Exception {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("webprotege-rebuild");
        assertThat(environment.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");

        OWLOntology ontology = OWLManager.createConcurrentOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(MINIMAL_TURTLE));

        assertThat(ontology.getClassesInSignature())
                .extracting(cls -> cls.getIRI().toString())
                .contains("http://example.org/smoke#Person");
    }
}
