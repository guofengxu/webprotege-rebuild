package wprebuild.app.integration;

import java.util.List;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Lists webhooks for a project. Requires {@code EDIT_PROJECT_SETTINGS}. */
public record ListWebhooks(ProjectId projectId) implements Command<List<Webhook>> {

    public ListWebhooks {
        Objects.requireNonNull(projectId, "project id");
    }
}
