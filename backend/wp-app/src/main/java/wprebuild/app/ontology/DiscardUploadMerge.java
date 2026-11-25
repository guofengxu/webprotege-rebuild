package wprebuild.app.ontology;

import java.util.Objects;
import java.util.UUID;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Drops a staged upload-and-merge preview (BS-14 / P6-50). */
public record DiscardUploadMerge(ProjectId projectId, UUID uploadId) implements Command<Void> {

    public DiscardUploadMerge {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(uploadId, "upload id");
    }
}
