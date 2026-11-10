package wprebuild.app.ontology.frame;

import java.util.List;
import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Read-only editing view of one data property.
 *
 * <p>{@code parents} are named super properties. {@code domains} are named classes and
 * {@code ranges} are datatypes. {@code functional} is true when a functional data-property axiom is
 * present. {@code otherAxioms} are Manchester text and are not edited from this view.
 */
public record DataPropertyFrame(
        EntityNode subject,
        List<AnnotationValue> annotations,
        List<EntityNode> parents,
        List<EntityNode> domains,
        List<EntityNode> ranges,
        boolean functional,
        List<String> otherAxioms) {

    public DataPropertyFrame {
        Objects.requireNonNull(subject, "subject");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        domains = List.copyOf(Objects.requireNonNull(domains, "domains"));
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        otherAxioms = List.copyOf(Objects.requireNonNull(otherAxioms, "other axioms"));
    }
}
