package wprebuild.app.ontology.frame;

import java.util.List;
import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Read-only editing view of one object property.
 *
 * <p>{@code parents} are named super properties. {@code domains} and {@code ranges} are named
 * classes. {@code otherAxioms} are the remaining subject axioms, rendered as Manchester text and
 * not edited from this view.
 */
public record ObjectPropertyFrame(
        EntityNode subject,
        List<AnnotationValue> annotations,
        List<EntityNode> parents,
        List<EntityNode> domains,
        List<EntityNode> ranges,
        List<ObjectPropertyCharacteristic> characteristics,
        List<EntityNode> inverses,
        List<String> otherAxioms) {

    public ObjectPropertyFrame {
        Objects.requireNonNull(subject, "subject");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        domains = List.copyOf(Objects.requireNonNull(domains, "domains"));
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        characteristics = List.copyOf(Objects.requireNonNull(characteristics, "characteristics"));
        inverses = List.copyOf(Objects.requireNonNull(inverses, "inverses"));
        otherAxioms = List.copyOf(Objects.requireNonNull(otherAxioms, "other axioms"));
    }
}
