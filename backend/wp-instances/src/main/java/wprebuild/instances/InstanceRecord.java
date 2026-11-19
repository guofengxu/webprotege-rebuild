package wprebuild.instances;

import java.util.Objects;

/**
 * One version of an instance. Attributes are JSON text stored as jsonb.
 */
public record InstanceRecord(String iri, String classIri, String attributesJson, String source, long ontologyRev) {

    public InstanceRecord {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(classIri, "classIri");
        Objects.requireNonNull(attributesJson, "attributesJson");
        Objects.requireNonNull(source, "source");
        if (iri.isBlank()) {
            throw new IllegalArgumentException("iri must not be blank");
        }
        if (classIri.isBlank()) {
            throw new IllegalArgumentException("classIri must not be blank");
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        String trimmed = attributesJson.stripLeading();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            throw new IllegalArgumentException("attributesJson must be a JSON object or array");
        }
    }
}
