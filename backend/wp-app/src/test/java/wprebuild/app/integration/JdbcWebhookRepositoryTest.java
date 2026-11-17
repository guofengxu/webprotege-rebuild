package wprebuild.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

@DatabaseTest
@Transactional
class JdbcWebhookRepositoryTest {

    private final JdbcWebhookRepository repository;
    private final JdbcClient jdbc;

    private ProjectId projectId;
    private UserId userId;

    JdbcWebhookRepositoryTest(JdbcWebhookRepository repository, JdbcClient jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void seedProject() {
        userId = new UserId("wh-" + UUID.randomUUID());
        projectId = new ProjectId(UUID.randomUUID());
        jdbc.sql("insert into users (user_id, display_name) values (?, ?)")
                .param(userId.value())
                .param("Webhook Tester")
                .update();
        jdbc.sql("""
                        insert into projects (
                            project_id, display_name, owner, created_at, created_by, modified_at, modified_by
                        ) values (?, 'Webhook', ?, now(), ?, now(), ?)
                        """)
                .param(projectId.value())
                .param(userId.value())
                .param(userId.value())
                .param(userId.value())
                .update();
    }

    @Test
    void insert_list_update_count_and_find() {
        UUID id = UUID.randomUUID();
        Webhook created = repository.insert(
                id,
                projectId,
                URI.create("https://203.0.113.10/hooks/a"),
                Set.of(WebhookEventType.PROJECT_CHANGED),
                "s3cret",
                true,
                userId);

        assertThat(created.webhookId()).isEqualTo(id);
        assertThat(created.hasSecret()).isTrue();
        assertThat(created.lastDelivery()).isNull();
        assertThat(repository.countByProject(projectId)).isEqualTo(1);
        assertThat(repository.listByProject(projectId))
                .extracting(Webhook::webhookId)
                .containsExactly(id);

        Webhook updated = repository
                .update(
                        id,
                        URI.create("https://203.0.113.11/hooks/b"),
                        Set.of(WebhookEventType.PROJECT_CHANGED),
                        null,
                        false)
                .orElseThrow();
        assertThat(updated.payloadUrl()).isEqualTo(URI.create("https://203.0.113.11/hooks/b"));
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.hasSecret()).isFalse();
        assertThat(repository.findById(id)).contains(updated);
    }

    @Test
    void delete_cascades_deliveries() {
        UUID webhookId = UUID.randomUUID();
        repository.insert(
                webhookId,
                projectId,
                URI.create("https://203.0.113.10/hooks/a"),
                Set.of(WebhookEventType.PROJECT_CHANGED),
                null,
                true,
                userId);
        insertDelivery(webhookId, 1L, "PENDING");
        insertDelivery(webhookId, 2L, "SUCCEEDED");

        assertThat(repository.delete(webhookId)).isTrue();
        assertThat(repository.findById(webhookId)).isEmpty();
        assertThat(jdbc.sql("select count(*) from webhook_deliveries where webhook_id = ?")
                        .param(webhookId)
                        .query((rs, row) -> rs.getInt(1))
                        .single())
                .isZero();
    }

    @Test
    void cancel_pending_only_affects_that_webhook() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        repository.insert(
                first,
                projectId,
                URI.create("https://203.0.113.10/a"),
                Set.of(WebhookEventType.PROJECT_CHANGED),
                null,
                true,
                userId);
        repository.insert(
                second,
                projectId,
                URI.create("https://203.0.113.10/b"),
                Set.of(WebhookEventType.PROJECT_CHANGED),
                null,
                true,
                userId);
        insertDelivery(first, 10L, "PENDING");
        insertDelivery(first, 11L, "SUCCEEDED");
        insertDelivery(second, 12L, "PENDING");

        assertThat(repository.cancelPending(first)).isEqualTo(1);

        List<WebhookDelivery> firstDeliveries = repository.listDeliveries(first, 10);
        assertThat(firstDeliveries).extracting(WebhookDelivery::status).containsExactly("SUCCEEDED", "CANCELLED");
        assertThat(repository.listDeliveries(second, 10))
                .extracting(WebhookDelivery::status)
                .containsExactly("PENDING");
    }

    @Test
    void list_deliveries_newest_first_and_last_delivery_summary() {
        UUID webhookId = UUID.randomUUID();
        repository.insert(
                webhookId,
                projectId,
                URI.create("https://203.0.113.10/hooks/a"),
                Set.of(WebhookEventType.PROJECT_CHANGED),
                null,
                true,
                userId);
        insertDelivery(webhookId, 100L, "FAILED");
        insertDelivery(webhookId, 101L, "SUCCEEDED");

        assertThat(repository.listDeliveries(webhookId, 1))
                .extracting(WebhookDelivery::status)
                .containsExactly("SUCCEEDED");

        Webhook loaded = repository.findById(webhookId).orElseThrow();
        assertThat(loaded.lastDelivery()).isNotNull();
        assertThat(loaded.lastDelivery().status()).isEqualTo("SUCCEEDED");
    }

    private void insertDelivery(UUID webhookId, long outboxId, String status) {
        jdbc.sql("""
                        insert into webhook_deliveries (
                            webhook_id, outbox_id, event_type, payload, status, attempts,
                            last_status_code, last_error, finished_at
                        ) values (
                            ?, ?, 'PROJECT_CHANGED', cast('{"ok":true}' as jsonb), ?, 1,
                            case
                                when ? = 'SUCCEEDED' then 200
                                when ? = 'FAILED' then 500
                                else null
                            end,
                            case when ? = 'FAILED' then 'boom' else null end,
                            case when ? in ('SUCCEEDED', 'FAILED', 'CANCELLED') then now() else null end
                        )
                        """)
                .param(webhookId)
                .param(outboxId)
                .param(status)
                .param(status)
                .param(status)
                .param(status)
                .param(status)
                .update();
    }
}
