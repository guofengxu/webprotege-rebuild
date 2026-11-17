package wprebuild.app.integration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.bus.ProjectExistenceCheck;
import wprebuild.app.bus.ProjectExistenceCheck.ProjectState;
import wprebuild.app.revision.OutboxConsumer;
import wprebuild.app.revision.OutboxMessage;

/**
 * Registers {@code PENDING} webhook deliveries for {@code REVISION_COMMITTED} outbox rows (P6-21).
 * Does not send HTTP; wakes {@link WebhookDispatcher} after the relay transaction commits.
 */
@Component
@ConditionalOnProperty(prefix = "wp.webhooks", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebhookOutboxConsumer implements OutboxConsumer {

    static final String CONSUMER_NAME = "webhooks";

    private static final String REVISION_COMMITTED = "REVISION_COMMITTED";

    private final JdbcWebhookRepository webhooks;

    private final ProjectExistenceCheck projects;

    private final JsonMapper json;

    private final ObjectProvider<WebhookDispatcher> dispatcher;

    public WebhookOutboxConsumer(
            JdbcWebhookRepository webhooks,
            ProjectExistenceCheck projects,
            JsonMapper json,
            ObjectProvider<WebhookDispatcher> dispatcher) {
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.json = Objects.requireNonNull(json, "json");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public StartPosition startFrom() {
        return StartPosition.LATEST;
    }

    @Override
    public long accept(List<OutboxMessage> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("outbox batch must not be empty");
        }
        boolean enqueued = false;
        for (OutboxMessage message : batch) {
            if (!REVISION_COMMITTED.equals(message.type())) {
                continue;
            }
            ProjectState state = projects.state(message.projectId());
            if (state != ProjectState.ACTIVE) {
                continue;
            }
            String payload = projectChangedPayload(message);
            int inserted = webhooks.enqueueDeliveries(
                    message.projectId(), message.id(), WebhookEventType.PROJECT_CHANGED, payload);
            enqueued |= inserted > 0;
        }
        if (enqueued) {
            wakeDispatcherAfterCommit();
        }
        return batch.getLast().id();
    }

    private String projectChangedPayload(OutboxMessage message) {
        try {
            JsonNode outbox = json.readTree(message.payloadJson());
            ObjectNode body = json.createObjectNode();
            body.put("projectId", outbox.path("projectId").asString());
            body.put("userId", outbox.path("userId").asString());
            body.put("revisionNumber", outbox.path("revNo").asLong(message.revNo()));
            Instant timestamp = Instant.parse(outbox.path("ts").asString());
            body.put("timestamp", timestamp.toEpochMilli());
            return json.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("invalid outbox payload for webhook id=" + message.id(), ex);
        }
    }

    private void wakeDispatcherAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            wakeDispatcher();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wakeDispatcher();
            }
        });
    }

    private void wakeDispatcher() {
        WebhookDispatcher bean = dispatcher.getIfAvailable();
        if (bean != null) {
            bean.wake();
        }
    }
}
