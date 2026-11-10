package wprebuild.app.events;

import java.util.Objects;

/** An entity named by a high-level event. {@code entityType} matches the frame API ({@code CLASS}, …). */
public record EventEntity(String iri, String entityType) {

    public EventEntity {
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
