package wprebuild.app.ontology;

import java.nio.file.Path;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.app.project.ProjectText;
import wprebuild.kernel.id.ProjectId;

/** Creates a project whose first revision is the uploaded ontology. Requires {@code UPLOAD_PROJECT}. */
public record ImportOntologyFromUpload(String name, String description, Path file) implements Command<ProjectId> {

    public ImportOntologyFromUpload {
        name = ProjectText.displayName(name);
        description = ProjectText.description(description);
        Objects.requireNonNull(file, "file");
    }
}
