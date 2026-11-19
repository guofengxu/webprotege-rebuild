package wprebuild.kernel.generator;

import java.util.List;
import java.util.Objects;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import wprebuild.kernel.change.OntologyKey;

/**
 * Result of comparing uploaded ontologies with the project (BS-14).
 *
 * <p>Lists are ordered: patches by ontology key, axioms and annotations by OWL API natural order.
 * Unchanged pairings are omitted from {@link #patches()}. {@link UploadMergeMode#ADDITIONS} plans
 * must not carry removals.
 */
public record UploadMergePlan(UploadMergeMode mode, List<OntologyPatch> patches, List<String> unmatchedUploads) {

    public UploadMergePlan {
        Objects.requireNonNull(mode, "mode");
        patches = List.copyOf(Objects.requireNonNull(patches, "patches"));
        unmatchedUploads = List.copyOf(Objects.requireNonNull(unmatchedUploads, "unmatched uploads"));
        if (mode == UploadMergeMode.ADDITIONS) {
            for (OntologyPatch patch : patches) {
                if (!patch.removedAxioms().isEmpty()
                        || !patch.removedAnnotations().isEmpty()) {
                    throw new IllegalArgumentException("ADDITIONS plan must not contain removals for "
                            + patch.key().value());
                }
            }
        }
    }

    /**
     * Diff for one project ontology.
     *
     * @param key project ontology key
     * @param addedAxioms axioms present in the upload but not in the project
     * @param removedAxioms axioms present in the project but not in the upload
     * @param addedAnnotations ontology annotations present in the upload but not in the project
     * @param removedAnnotations ontology annotations present in the project but not in the upload
     */
    public record OntologyPatch(
            OntologyKey key,
            List<OWLAxiom> addedAxioms,
            List<OWLAxiom> removedAxioms,
            List<OWLAnnotation> addedAnnotations,
            List<OWLAnnotation> removedAnnotations) {

        public OntologyPatch {
            Objects.requireNonNull(key, "ontology key");
            addedAxioms = List.copyOf(Objects.requireNonNull(addedAxioms, "added axioms"));
            removedAxioms = List.copyOf(Objects.requireNonNull(removedAxioms, "removed axioms"));
            addedAnnotations = List.copyOf(Objects.requireNonNull(addedAnnotations, "added annotations"));
            removedAnnotations = List.copyOf(Objects.requireNonNull(removedAnnotations, "removed annotations"));
        }

        boolean isEmpty() {
            return addedAxioms.isEmpty()
                    && removedAxioms.isEmpty()
                    && addedAnnotations.isEmpty()
                    && removedAnnotations.isEmpty();
        }
    }
}
