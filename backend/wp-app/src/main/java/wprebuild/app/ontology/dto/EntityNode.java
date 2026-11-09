package wprebuild.app.ontology.dto;

import java.util.Objects;

/**
 * One entity as shown in a hierarchy or search result.
 *
 * <p>{@code entityType} is the OWL entity type name ({@code CLASS}, {@code OBJECT_PROPERTY}, and so
 * on). {@code deprecated} is true when the entity has an {@code owl:deprecated true} annotation.
 */
public record EntityNode(String iri, String entityType, String displayName, boolean hasChildren, boolean deprecated) {

    public EntityNode {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(entityType, "entity type");
        Objects.requireNonNull(displayName, "display name");
        if (iri.isBlank()) {
            throw new IllegalArgumentException("iri is blank");
        }
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("entity type is blank");
        }
    }
}
