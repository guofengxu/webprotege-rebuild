package wprebuild.app.ontology.frame;

import java.util.List;
import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Read-only editing view of one class.
 *
 * <p>{@code parents} are named superclasses. {@code otherAxioms} are the remaining subject axioms,
 * rendered as Manchester text and not edited from this view.
 */
public record ClassFrame(
        EntityNode subject,
        List<AnnotationValue> annotations,
        List<EntityNode> parents,
        List<PropertyValue> propertyValues,
        List<String> otherAxioms) {

    public ClassFrame {
        Objects.requireNonNull(subject, "subject");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        propertyValues = List.copyOf(Objects.requireNonNull(propertyValues, "property values"));
        otherAxioms = List.copyOf(Objects.requireNonNull(otherAxioms, "other axioms"));
    }
}
