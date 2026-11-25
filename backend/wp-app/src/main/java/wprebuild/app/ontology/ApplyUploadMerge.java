package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Re-parses a staged upload and applies it with the preview-time head as {@code expectedHead}
 * (BS-14 / P6-50).
 */
public record ApplyUploadMerge(ProjectId projectId, UUID uploadId, long baseRevision, String description)
        implements Command<ApplyUploadMerge.Result> {

    public ApplyUploadMerge {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(uploadId, "upload id");
        description = description == null ? "" : description;
        if (baseRevision < 0) {
            throw new IllegalArgumentException("base revision must be non-negative");
        }
    }

    /** New revision number, or {@code null} when the merge produced no effective changes. */
    public record Result(Long revision) {

        public Body body() {
            return new Body(revision);
        }
    }

    @Schema(name = "ApplyUploadMergeResult")
    public record Body(@Schema(nullable = true) Long revision) {}
}
