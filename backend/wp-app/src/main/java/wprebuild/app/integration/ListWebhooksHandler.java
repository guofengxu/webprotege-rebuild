package wprebuild.app.integration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class ListWebhooksHandler implements CommandHandler<ListWebhooks, List<Webhook>> {

    private final JdbcWebhookRepository webhooks;

    ListWebhooksHandler(JdbcWebhookRepository webhooks) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
    }

    @Override
    public Class<ListWebhooks> commandType() {
        return ListWebhooks.class;
    }

    @Override
    public Optional<ProjectId> project(ListWebhooks command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(ListWebhooks command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_PROJECT_SETTINGS);
    }

    @Override
    public List<Webhook> handle(ListWebhooks command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return webhooks.listByProject(command.projectId());
    }
}
