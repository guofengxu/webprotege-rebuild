package wprebuild.app.ontology.frame;

import java.util.List;
import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Read-only editing view of one named individual.
 *
 * <p>{@code types} are asserted named classes. {@code objectPropertyAssertions} and
 * {@code dataPropertyAssertions} are assertions where this individual is the subject.
 * {@code sameIndividuals} are the other individual in a binary {@code SameIndividual} axiom.
 * A larger same-individual axiom stays in {@code otherAxioms}.
 * {@code otherAxioms} are the remaining subject axioms, rendered as Manchester text and not edited
 * from this view. Inferred types are not part of this frame.
 */
public record NamedIndividualFrame(
        EntityNode subject,
        List<AnnotationValue> annotations,
        List<EntityNode> types,
        List<ObjectPropertyAssertion> objectPropertyAssertions,
        List<DataPropertyAssertion> dataPropertyAssertions,
        List<EntityNode> sameIndividuals,
        List<String> otherAxioms) {

    public NamedIndividualFrame {
        Objects.requireNonNull(subject, "subject");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        types = List.copyOf(Objects.requireNonNull(types, "types"));
        objectPropertyAssertions =
                List.copyOf(Objects.requireNonNull(objectPropertyAssertions, "object property assertions"));
        dataPropertyAssertions =
                List.copyOf(Objects.requireNonNull(dataPropertyAssertions, "data property assertions"));
        sameIndividuals = List.copyOf(Objects.requireNonNull(sameIndividuals, "same individuals"));
        otherAxioms = List.copyOf(Objects.requireNonNull(otherAxioms, "other axioms"));
    }
}
