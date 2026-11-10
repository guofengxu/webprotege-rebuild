package wprebuild.app.ontology.frame;

import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/** One object-property assertion whose subject is the frame individual. */
public record ObjectPropertyAssertion(EntityNode property, EntityNode individual) {

    public ObjectPropertyAssertion {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(individual, "individual");
    }
}
