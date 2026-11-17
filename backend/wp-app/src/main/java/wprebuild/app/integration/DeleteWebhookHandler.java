package wprebuild.app.integration;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

@Component
class DeleteWebhookHandler implements CommandHandler<DeleteWebhook, Void> {

    private static final Logger log = LoggerFactory.getLogger(DeleteWebhookHandler.class);

    private final JdbcWebhookRepository webhooks;

    DeleteWebhookHandler(JdbcWebhookRepository webhooks) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
    }

    @Override
    public Class<DeleteWebhook> commandType() {
        return DeleteWebhook.class;
    }

    @Override
    public Optional<ProjectId> project(DeleteWebhook command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(DeleteWebhook command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_PROJECT_SETTINGS);
    }

    @Override
    public Void handle(DeleteWebhook command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        Webhook webhook =
                webhooks.findById(command.webhookId()).orElseThrow(() -> new NotFoundException("webhook not found"));
        if (!webhook.projectId().equals(command.projectId())) {
            throw new NotFoundException("webhook not found");
        }
        if (!webhooks.delete(command.webhookId())) {
            throw new NotFoundException("webhook not found");
        }
        log.info(
                "deleted webhook {} for project {} host {}",
                command.webhookId(),
                command.projectId(),
                webhook.payloadUrl().getHost());
        return null;
    }
}
