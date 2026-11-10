package wprebuild.app.ontology.frame;

import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/** One data-property assertion whose subject is the frame individual. {@code value} is the literal. */
public record DataPropertyAssertion(EntityNode property, LiteralValue value) {

    public DataPropertyAssertion {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(value, "value");
    }
}
