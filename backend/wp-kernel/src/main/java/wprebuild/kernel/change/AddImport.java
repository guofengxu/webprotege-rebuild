package wprebuild.kernel.change;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;

/** Adds an import of {@link #iri()}. */
public record AddImport(OntologyKey ontology, IRI iri) implements OntologyChange {

    public AddImport {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(iri, "iri");
    }
}
