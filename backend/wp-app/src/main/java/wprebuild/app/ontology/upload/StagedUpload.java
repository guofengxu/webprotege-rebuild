package wprebuild.app.ontology.upload;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** One staged upload file owned by a user for a project. */
public record StagedUpload(UUID uploadId, ProjectId projectId, UserId userId, Path file, StagedUploadMeta meta) {

    public StagedUpload {
        Objects.requireNonNull(uploadId, "upload id");
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(meta, "meta");
    }
}
