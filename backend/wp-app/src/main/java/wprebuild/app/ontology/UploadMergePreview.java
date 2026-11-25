package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import wprebuild.kernel.generator.UploadMergeMode;

/** Preview of an upload-and-merge before it is applied (BS-14 / P6-50). */
@Schema(name = "UploadMergePreview")
public record UploadMergePreview(
        UUID uploadId,
        UploadMergeMode mode,
        long baseRevision,
        List<OntologyPatchSummary> ontologies,
        List<String> unmatchedUploads,
        long totalAdded,
        long totalRemoved) {

    public static final int PREVIEW_AXIOM_LIMIT = 200;

    public UploadMergePreview {
        Objects.requireNonNull(uploadId, "upload id");
        Objects.requireNonNull(mode, "mode");
        ontologies = List.copyOf(Objects.requireNonNull(ontologies, "ontologies"));
        unmatchedUploads = List.copyOf(Objects.requireNonNull(unmatchedUploads, "unmatched uploads"));
        if (baseRevision < 0) {
            throw new IllegalArgumentException("base revision must be non-negative");
        }
        if (totalAdded < 0 || totalRemoved < 0) {
            throw new IllegalArgumentException("totals must be non-negative");
        }
    }

    /** Per-ontology diff counts and truncated Manchester axiom texts. */
    @Schema(name = "OntologyPatchSummary")
    public record OntologyPatchSummary(
            String key,
            String ontologyIri,
            long addedAxiomCount,
            long removedAxiomCount,
            long addedAnnotationCount,
            long removedAnnotationCount,
            List<String> addedAxioms,
            List<String> removedAxioms) {

        public OntologyPatchSummary {
            Objects.requireNonNull(key, "ontology key");
            addedAxioms = List.copyOf(Objects.requireNonNull(addedAxioms, "added axioms"));
            removedAxioms = List.copyOf(Objects.requireNonNull(removedAxioms, "removed axioms"));
            if (addedAxiomCount < 0
                    || removedAxiomCount < 0
                    || addedAnnotationCount < 0
                    || removedAnnotationCount < 0) {
                throw new IllegalArgumentException("patch counts must be non-negative");
            }
        }
    }
}
