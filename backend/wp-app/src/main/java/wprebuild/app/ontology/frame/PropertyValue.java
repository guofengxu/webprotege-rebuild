package wprebuild.app.ontology.frame;

import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/** One supported restriction on the frame subject. */
public record PropertyValue(EntityNode property, PropertyValueKind kind, ValueNode filler) {

    public PropertyValue {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(filler, "filler");
    }
}
