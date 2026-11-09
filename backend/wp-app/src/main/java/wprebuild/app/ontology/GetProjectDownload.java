package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Optional;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/** Downloads one project revision. An empty revision means the current head. Requires {@code DOWNLOAD_PROJECT}. */
public record GetProjectDownload(ProjectId projectId, Optional<RevisionNumber> revision, DownloadFormat format)
        implements Command<ProjectDownload> {

    public GetProjectDownload {
        Objects.requireNonNull(projectId, "project id");
        revision = revision == null ? Optional.empty() : revision;
        Objects.requireNonNull(format, "format");
    }
}
