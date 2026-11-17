package wprebuild.app.integration;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.ConflictException;
import wprebuild.kernel.id.ProjectId;

@Component
class CreateWebhookHandler implements CommandHandler<CreateWebhook, Webhook> {

    static final String WEBHOOK_LIMIT_REACHED = "WEBHOOK_LIMIT_REACHED";

    private static final Logger log = LoggerFactory.getLogger(CreateWebhookHandler.class);

    private final JdbcWebhookRepository webhooks;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookProperties properties;

    CreateWebhookHandler(JdbcWebhookRepository webhooks, WebhookUrlPolicy urlPolicy, WebhookProperties properties) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "url policy");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Class<CreateWebhook> commandType() {
        return CreateWebhook.class;
    }

    @Override
    public Optional<ProjectId> project(CreateWebhook command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(CreateWebhook command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_PROJECT_SETTINGS);
    }

    @Override
    public Webhook handle(CreateWebhook command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        WebhookUrlPolicy.Result check = urlPolicy.check(command.payloadUrl());
        if (check != WebhookUrlPolicy.Result.ALLOWED) {
            throw WebhookRejectedException.fromUrlPolicy(check);
        }
        if (webhooks.countByProject(command.projectId()) >= properties.maxPerProject()) {
            throw new ConflictException(WEBHOOK_LIMIT_REACHED, "project already has the maximum number of webhooks");
        }
        URI url = URI.create(command.payloadUrl());
        UUID webhookId = UUID.randomUUID();
        Webhook created = webhooks.insert(
                webhookId,
                command.projectId(),
                url,
                command.eventTypes(),
                command.secret(),
                command.enabled(),
                context.user());
        log.info("created webhook {} for project {} host {}", webhookId, command.projectId(), url.getHost());
        return created;
    }
}
