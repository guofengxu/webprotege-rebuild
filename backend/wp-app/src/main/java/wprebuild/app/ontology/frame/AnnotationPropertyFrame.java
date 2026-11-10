package wprebuild.app.ontology.frame;

import java.util.List;
import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Read-only editing view of one annotation property.
 *
 * <p>{@code parents} are named super properties. {@code domains} and {@code ranges} are IRIs, which
 * need not name an entity. {@code otherAxioms} are Manchester text and are not edited from this
 * view.
 */
public record AnnotationPropertyFrame(
        EntityNode subject,
        List<AnnotationValue> annotations,
        List<EntityNode> parents,
        List<String> domains,
        List<String> ranges,
        List<String> otherAxioms) {

    public AnnotationPropertyFrame {
        Objects.requireNonNull(subject, "subject");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        domains = List.copyOf(Objects.requireNonNull(domains, "domains"));
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        otherAxioms = List.copyOf(Objects.requireNonNull(otherAxioms, "other axioms"));
    }
}
