package wprebuild.instances;

import java.util.Objects;

/** One directed relationship between two instances. */
public record LinkRecord(String subjectIri, String propertyIri, String objectIri) {

    public LinkRecord {
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(propertyIri, "propertyIri");
        Objects.requireNonNull(objectIri, "objectIri");
        if (subjectIri.isBlank() || propertyIri.isBlank() || objectIri.isBlank()) {
            throw new IllegalArgumentException("link IRIs must not be blank");
        }
    }
}
