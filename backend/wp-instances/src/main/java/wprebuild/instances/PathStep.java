package wprebuild.instances;

import java.util.Objects;

/** One hop in a fixed property path for {@link TraceQueries#follow}. */
public record PathStep(String propertyIri, Direction direction) {

    public PathStep {
        Objects.requireNonNull(propertyIri, "propertyIri");
        Objects.requireNonNull(direction, "direction");
        if (propertyIri.isBlank()) {
            throw new IllegalArgumentException("propertyIri must not be blank");
        }
    }
}
