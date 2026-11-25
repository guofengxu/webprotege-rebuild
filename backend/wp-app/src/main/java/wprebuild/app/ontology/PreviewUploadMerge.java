package wprebuild.app.ontology;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import wprebuild.app.bus.Command;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.generator.UploadMergeMode;
import wprebuild.kernel.id.ProjectId;

/**
 * Parses an uploaded ontology, plans the merge under the project read lock, and stages the file for
 * later apply (BS-14 / P6-50).
 */
public record PreviewUploadMerge(ProjectId projectId, Path file, UploadMergeMode mode, Optional<OntologyKey> target)
        implements Command<UploadMergePreview> {

    public PreviewUploadMerge {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(target, "target");
        if (mode == UploadMergeMode.ADDITIONS && target.isEmpty()) {
            throw new IllegalArgumentException("additions mode requires a target ontology");
        }
    }
}
