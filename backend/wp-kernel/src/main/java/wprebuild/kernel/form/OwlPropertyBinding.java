package wprebuild.kernel.form;

import java.util.Objects;
import org.semanticweb.owlapi.model.OWLEntity;

/** Field bound to one OWL property (annotation, data, or object). */
public record OwlPropertyBinding(OWLEntity property) implements OwlBinding {

    public OwlPropertyBinding {
        Objects.requireNonNull(property, "property");
    }
}
