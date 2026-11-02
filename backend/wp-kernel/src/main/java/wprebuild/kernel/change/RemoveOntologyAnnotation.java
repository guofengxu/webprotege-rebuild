package wprebuild.kernel.change;

import java.util.Objects;
import org.semanticweb.owlapi.model.OWLAnnotation;

/** Removes one ontology annotation. */
public record RemoveOntologyAnnotation(OntologyKey ontology, OWLAnnotation annotation) implements OntologyChange {

    public RemoveOntologyAnnotation {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(annotation, "annotation");
    }
}
