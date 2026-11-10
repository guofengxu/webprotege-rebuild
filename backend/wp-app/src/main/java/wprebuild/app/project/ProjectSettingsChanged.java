package wprebuild.app.project;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;

/**
 * A stored project setting was inserted or replaced.
 *
 * <p>{@code key} is one of {@link ProjectSettingKeys}. The ontology module rebuilds the display-name
 * view when the key is {@code display-names}.
 */
public record ProjectSettingsChanged(ProjectId projectId, String key) {

    public ProjectSettingsChanged {
        Objects.requireNonNull(projectId, "project id");
        key = ProjectSettingKeys.check(key);
    }
}
