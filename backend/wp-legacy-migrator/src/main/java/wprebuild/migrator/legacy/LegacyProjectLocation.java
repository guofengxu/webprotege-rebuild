package wprebuild.migrator.legacy;

import java.nio.file.Path;
import java.util.Objects;

/** A project directory under {@code data-store/project-data} and its change-log path. */
public record LegacyProjectLocation(String projectId, Path changeDataBinary) {

    public LegacyProjectLocation {
        Objects.requireNonNull(projectId, "projectId");
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is blank");
        }
        changeDataBinary = Objects.requireNonNull(changeDataBinary, "changeDataBinary");
    }
}
