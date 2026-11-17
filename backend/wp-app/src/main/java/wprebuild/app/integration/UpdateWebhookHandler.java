package wprebuild.app.integration;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

/**
 * Updates a webhook. Not {@code final}: {@code @Transactional} uses a subclass proxy so disable +
 * cancel-pending stay in one transaction.
 */
@Service
class UpdateWebhookHandler implements CommandHandler<UpdateWebhook, Webhook> {

    private static final Logger log = LoggerFactory.getLogger(UpdateWebhookHandler.class);

    private final JdbcWebhookRepository webhooks;
    private final WebhookUrlPolicy urlPolicy;

    UpdateWebhookHandler(JdbcWebhookRepository webhooks, WebhookUrlPolicy urlPolicy) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "url policy");
    }

    @Override
    public Class<UpdateWebhook> commandType() {
        return UpdateWebhook.class;
    }

    @Override
    public Optional<ProjectId> project(UpdateWebhook command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(UpdateWebhook command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_PROJECT_SETTINGS);
    }

    @Override
    @Transactional
    public Webhook handle(UpdateWebhook command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        Webhook existing = requireOwned(command.projectId(), command.webhookId());
        WebhookUrlPolicy.Result check = urlPolicy.check(command.payloadUrl());
        if (check != WebhookUrlPolicy.Result.ALLOWED) {
            throw WebhookRejectedException.fromUrlPolicy(check);
        }
        URI url = URI.create(command.payloadUrl());
        webhooks.update(command.webhookId(), url, command.eventTypes(), command.secretChange(), command.enabled())
                .orElseThrow(() -> new NotFoundException("webhook not found"));
        if (existing.enabled() && !command.enabled()) {
            int cancelled = webhooks.cancelPending(command.webhookId());
            log.info(
                    "disabled webhook {} for project {} host {}; cancelled {} pending deliveries",
                    command.webhookId(),
                    command.projectId(),
                    url.getHost(),
                    cancelled);
        } else {
            log.info(
                    "updated webhook {} for project {} host {}",
                    command.webhookId(),
                    command.projectId(),
                    url.getHost());
        }
        // Re-read so lastDelivery reflects CANCELLED after disable.
        return webhooks.findById(command.webhookId()).orElseThrow(() -> new NotFoundException("webhook not found"));
    }

    private Webhook requireOwned(ProjectId projectId, UUID webhookId) {
        Webhook webhook = webhooks.findById(webhookId).orElseThrow(() -> new NotFoundException("webhook not found"));
        if (!webhook.projectId().equals(projectId)) {
            throw new NotFoundException("webhook not found");
        }
        return webhook;
    }
}
