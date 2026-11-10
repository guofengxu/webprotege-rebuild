package wprebuild.app.project;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Replaces one project setting. The bus requires the action for that key. */
public record PutProjectSetting(ProjectId projectId, String key, JsonNode document) implements Command<JsonNode> {

    public PutProjectSetting {
        Objects.requireNonNull(projectId, "project id");
        key = ProjectSettingKeys.check(key);
        Objects.requireNonNull(document, "document");
    }
}
