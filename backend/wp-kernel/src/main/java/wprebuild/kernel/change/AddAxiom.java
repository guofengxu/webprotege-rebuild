package wprebuild.kernel.change;

import java.util.Objects;
import org.semanticweb.owlapi.model.OWLAxiom;

/**
 * Adds one axiom. The payload is {@link #owlAxiom()} because {@link OntologyChange#axiom()} returns
 * an {@code Optional} for every change kind.
 */
public record AddAxiom(OntologyKey ontology, OWLAxiom owlAxiom) implements OntologyChange {

    public AddAxiom {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(owlAxiom, "axiom");
    }
}
