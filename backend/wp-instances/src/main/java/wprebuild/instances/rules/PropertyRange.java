package wprebuild.instances.rules;

import java.util.Objects;

/** Declared named class or datatype range from the projection tables. */
public record PropertyRange(String rangeIri, RangeKind kind) {

    public enum RangeKind {
        CLASS,
        DATATYPE
    }

    public PropertyRange {
        Objects.requireNonNull(rangeIri, "rangeIri");
        Objects.requireNonNull(kind, "kind");
        if (rangeIri.isBlank()) {
            throw new IllegalArgumentException("rangeIri must not be blank");
        }
    }
}
