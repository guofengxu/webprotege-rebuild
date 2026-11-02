package wprebuild.kernel.change;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;

/** Removes an import of {@link #iri()}. */
public record RemoveImport(OntologyKey ontology, IRI iri) implements OntologyChange {

    public RemoveImport {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(iri, "iri");
    }
}
