package wprebuild.kernel.change;

import java.util.Objects;
import org.semanticweb.owlapi.model.OWLAxiom;

/** Removes one axiom. The payload is {@link #owlAxiom()} because {@link OntologyChange#axiom()} is optional. */
public record RemoveAxiom(OntologyKey ontology, OWLAxiom owlAxiom) implements OntologyChange {

    public RemoveAxiom {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(owlAxiom, "axiom");
    }
}
