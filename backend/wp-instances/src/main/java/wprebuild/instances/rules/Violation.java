package wprebuild.instances.rules;

import java.util.Objects;

/**
 * One closed-world write gate failure. {@code code} is one of {@code UNKNOWN_CLASS},
 * {@code UNKNOWN_PROPERTY}, {@code DEPRECATED_CLASS}, {@code DOMAIN_MISMATCH},
 * {@code RANGE_MISMATCH}, {@code DATATYPE_MISMATCH}.
 */
public record Violation(String code, String subjectIri, String propertyIri, String message) {

    public Violation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(message, "message");
        if (propertyIri == null) {
            propertyIri = "";
        }
    }
}
