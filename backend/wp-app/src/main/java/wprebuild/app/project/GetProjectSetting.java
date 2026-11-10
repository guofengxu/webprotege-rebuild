package wprebuild.app.project;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Reads one project setting. The bus requires {@code VIEW_PROJECT}. */
public record GetProjectSetting(ProjectId projectId, String key) implements Command<JsonNode> {

    public GetProjectSetting {
        Objects.requireNonNull(projectId, "project id");
        key = ProjectSettingKeys.check(key);
    }
}
