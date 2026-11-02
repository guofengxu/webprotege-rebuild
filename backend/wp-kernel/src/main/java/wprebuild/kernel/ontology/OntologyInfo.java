package wprebuild.kernel.ontology;

import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.change.OntologyKey;

/** Identity of one ontology in a project. A version IRI requires an ontology IRI. */
public record OntologyInfo(OntologyKey key, Optional<IRI> ontologyIri, Optional<IRI> versionIri) {

    public OntologyInfo {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ontologyIri, "ontologyIri");
        Objects.requireNonNull(versionIri, "versionIri");
        if (versionIri.isPresent() && ontologyIri.isEmpty()) {
            throw new IllegalArgumentException("version IRI requires an ontology IRI");
        }
    }
}
