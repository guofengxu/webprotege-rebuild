package wprebuild.app.ontology.upload;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.generator.UploadMergeMode;

/** Metadata recorded with a staged upload until preview/apply (P6-50). */
public record StagedUploadMeta(
        UploadMergeMode mode, Optional<OntologyKey> targetOntology, long baseRevision, Instant createdAt) {

    public StagedUploadMeta {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(targetOntology, "target ontology");
        Objects.requireNonNull(createdAt, "created at");
        if (baseRevision < 0) {
            throw new IllegalArgumentException("base revision must be non-negative");
        }
        if (mode == UploadMergeMode.ADDITIONS && targetOntology.isEmpty()) {
            throw new IllegalArgumentException("additions mode requires a target ontology");
        }
    }
}
