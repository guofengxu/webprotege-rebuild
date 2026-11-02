package wprebuild.kernel.change;

import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;

/**
 * Registers an ontology key. A version IRI is allowed only together with an ontology IRI.
 * Inverting this change is refused (BS-04).
 */
public record DeclareOntology(OntologyKey ontology, Optional<IRI> ontologyIri, Optional<IRI> versionIri)
        implements OntologyChange {

    public DeclareOntology {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(ontologyIri, "ontologyIri");
        Objects.requireNonNull(versionIri, "versionIri");
        if (versionIri.isPresent() && ontologyIri.isEmpty()) {
            throw new IllegalArgumentException("version IRI requires an ontology IRI");
        }
    }
}
