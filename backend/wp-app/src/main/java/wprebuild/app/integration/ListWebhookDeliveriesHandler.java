package wprebuild.app.integration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

@Component
class ListWebhookDeliveriesHandler implements CommandHandler<ListWebhookDeliveries, List<WebhookDelivery>> {

    private final JdbcWebhookRepository webhooks;

    ListWebhookDeliveriesHandler(JdbcWebhookRepository webhooks) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
    }

    @Override
    public Class<ListWebhookDeliveries> commandType() {
        return ListWebhookDeliveries.class;
    }

    @Override
    public Optional<ProjectId> project(ListWebhookDeliveries command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(ListWebhookDeliveries command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_PROJECT_SETTINGS);
    }

    @Override
    public List<WebhookDelivery> handle(ListWebhookDeliveries command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        Webhook webhook =
                webhooks.findById(command.webhookId()).orElseThrow(() -> new NotFoundException("webhook not found"));
        if (!webhook.projectId().equals(command.projectId())) {
            throw new NotFoundException("webhook not found");
        }
        return webhooks.listDeliveries(command.webhookId(), command.limit());
    }
}
