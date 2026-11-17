package wprebuild.app.ontology.dto;

import java.util.Objects;

/**
 * Identity of one OWL entity in an API response. {@code entityType} matches the frame API
 * ({@code CLASS}, {@code OBJECT_PROPERTY}, …).
 */
public record EntityRef(String iri, String entityType) {

    public EntityRef {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(entityType, "entity type");
        if (iri.isBlank()) {
            throw new IllegalArgumentException("iri is blank");
        }
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("entity type is blank");
        }
    }
}
