package wprebuild.app.ontology.frame;

import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/** An individual, class, property, datatype, or other IRI filler. */
public record IriValue(EntityNode entity) implements ValueNode {

    public IriValue {
        Objects.requireNonNull(entity, "entity");
    }
}
