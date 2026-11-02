package wprebuild.kernel.change;

import java.util.Objects;
import org.semanticweb.owlapi.model.OWLAnnotation;

/** Adds one ontology annotation. */
public record AddOntologyAnnotation(OntologyKey ontology, OWLAnnotation annotation) implements OntologyChange {

    public AddOntologyAnnotation {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(annotation, "annotation");
    }
}
