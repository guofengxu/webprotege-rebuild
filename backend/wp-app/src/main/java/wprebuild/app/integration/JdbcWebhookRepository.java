package wprebuild.app.integration;

import java.net.URI;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * PostgreSQL storage for {@code project_webhooks} and read/cancel helpers for {@code webhook_deliveries}.
 * Secrets are written but never returned on {@link Webhook}.
 */
@Repository
public class JdbcWebhookRepository {

    private static final String WEBHOOK_SELECT = """
            select w.webhook_id, w.project_id, w.payload_url, w.event_types,
                   (w.secret is not null and w.secret <> '') as has_secret, w.enabled,
                   w.created_by, w.created_at, w.updated_at,
                   d.status as ld_status, d.last_status_code as ld_status_code,
                   d.finished_at as ld_finished_at, d.last_error as ld_error
            from project_webhooks w
            left join lateral (
                select status, last_status_code, finished_at, last_error
                from webhook_deliveries
                where webhook_id = w.webhook_id
                order by delivery_id desc
                limit 1
            ) d on true
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcWebhookRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbc template");
    }

    public List<Webhook> listByProject(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return List.copyOf(jdbc.sql(WEBHOOK_SELECT + """
                        where w.project_id = ?
                        order by w.created_at, w.webhook_id
                        """)
                .param(projectId.value())
                .query(this::mapWebhook)
                .list());
    }

    public Optional<Webhook> findById(UUID webhookId) {
        Objects.requireNonNull(webhookId, "webhook id");
        return jdbc.sql(WEBHOOK_SELECT + " where w.webhook_id = ?")
                .param(webhookId)
                .query(this::mapWebhook)
                .optional();
    }

    public Webhook insert(
            UUID webhookId,
            ProjectId projectId,
            URI payloadUrl,
            Set<WebhookEventType> eventTypes,
            String secret,
            boolean enabled,
            UserId createdBy) {
        Objects.requireNonNull(webhookId, "webhook id");
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(payloadUrl, "payload url");
        Objects.requireNonNull(eventTypes, "event types");
        Objects.requireNonNull(createdBy, "created by");
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("event types must not be empty");
        }
        jdbcTemplate.update("""
                insert into project_webhooks (
                    webhook_id, project_id, payload_url, event_types, secret, enabled, created_by
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, (PreparedStatement ps) -> {
            ps.setObject(1, webhookId);
            ps.setObject(2, projectId.value());
            ps.setString(3, payloadUrl.toString());
            ps.setArray(4, textArray(ps, eventTypes));
            ps.setString(5, secret);
            ps.setBoolean(6, enabled);
            ps.setString(7, createdBy.value());
        });
        return findById(webhookId).orElseThrow(() -> new IllegalStateException("webhook was not stored"));
    }

    public Optional<Webhook> update(
            UUID webhookId,
            URI payloadUrl,
            Set<WebhookEventType> eventTypes,
            SecretChange secretChange,
            boolean enabled) {
        Objects.requireNonNull(webhookId, "webhook id");
        Objects.requireNonNull(payloadUrl, "payload url");
        Objects.requireNonNull(eventTypes, "event types");
        Objects.requireNonNull(secretChange, "secret change");
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("event types must not be empty");
        }
        int updated = switch (secretChange) {
            case SecretChange.Keep() ->
                jdbcTemplate.update("""
                            update project_webhooks
                            set payload_url = ?,
                                event_types = ?,
                                enabled = ?,
                                updated_at = now()
                            where webhook_id = ?
                            """, (PreparedStatement ps) -> {
                    ps.setString(1, payloadUrl.toString());
                    ps.setArray(2, textArray(ps, eventTypes));
                    ps.setBoolean(3, enabled);
                    ps.setObject(4, webhookId);
                });
            case SecretChange.Clear() -> updateSecret(webhookId, payloadUrl, eventTypes, null, enabled);
            case SecretChange.Replace(String secret) ->
                updateSecret(webhookId, payloadUrl, eventTypes, secret, enabled);
        };
        if (updated == 0) {
            return Optional.empty();
        }
        return findById(webhookId);
    }

    private int updateSecret(
            UUID webhookId, URI payloadUrl, Set<WebhookEventType> eventTypes, String secret, boolean enabled) {
        return jdbcTemplate.update("""
                update project_webhooks
                set payload_url = ?,
                    event_types = ?,
                    secret = ?,
                    enabled = ?,
                    updated_at = now()
                where webhook_id = ?
                """, (PreparedStatement ps) -> {
            ps.setString(1, payloadUrl.toString());
            ps.setArray(2, textArray(ps, eventTypes));
            ps.setString(3, secret);
            ps.setBoolean(4, enabled);
            ps.setObject(5, webhookId);
        });
    }

    public boolean delete(UUID webhookId) {
        Objects.requireNonNull(webhookId, "webhook id");
        return jdbc.sql("delete from project_webhooks where webhook_id = ?")
                        .param(webhookId)
                        .update()
                > 0;
    }

    public int countByProject(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return jdbc.sql("select count(*) from project_webhooks where project_id = ?")
                .param(projectId.value())
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    public List<WebhookDelivery> listDeliveries(UUID webhookId, int limit) {
        Objects.requireNonNull(webhookId, "webhook id");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return List.copyOf(jdbc.sql("""
                        select delivery_id, status, attempts, last_status_code, last_error,
                               created_at, finished_at
                        from webhook_deliveries
                        where webhook_id = ?
                        order by delivery_id desc
                        limit ?
                        """)
                .param(webhookId)
                .param(limit)
                .query(this::mapDelivery)
                .list());
    }

    /** Marks every {@code PENDING} delivery for the webhook as {@code CANCELLED}. */
    public int cancelPending(UUID webhookId) {
        Objects.requireNonNull(webhookId, "webhook id");
        return jdbc.sql("""
                        update webhook_deliveries
                        set status = 'CANCELLED',
                            finished_at = now()
                        where webhook_id = ?
                          and status = 'PENDING'
                        """).param(webhookId).update();
    }

    private Webhook mapWebhook(ResultSet rs, int row) throws SQLException {
        String ldStatus = rs.getString("ld_status");
        DeliverySummary lastDelivery = ldStatus == null
                ? null
                : new DeliverySummary(
                        ldStatus,
                        (Integer) rs.getObject("ld_status_code"),
                        instantOrNull(rs, "ld_finished_at"),
                        rs.getString("ld_error"));
        return new Webhook(
                rs.getObject("webhook_id", UUID.class),
                new ProjectId(rs.getObject("project_id", UUID.class)),
                URI.create(rs.getString("payload_url")),
                readEventTypes(rs),
                rs.getBoolean("has_secret"),
                rs.getBoolean("enabled"),
                new UserId(rs.getString("created_by")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                lastDelivery);
    }

    private WebhookDelivery mapDelivery(ResultSet rs, int row) throws SQLException {
        return new WebhookDelivery(
                rs.getLong("delivery_id"),
                rs.getString("status"),
                rs.getInt("attempts"),
                (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instantOrNull(rs, "finished_at"));
    }

    private static Set<WebhookEventType> readEventTypes(ResultSet rs) throws SQLException {
        Array array = rs.getArray("event_types");
        String[] values = array == null ? new String[0] : (String[]) array.getArray();
        EnumSet<WebhookEventType> types = EnumSet.noneOf(WebhookEventType.class);
        for (String value : values) {
            types.add(WebhookEventType.valueOf(value));
        }
        return Set.copyOf(new LinkedHashSet<>(types));
    }

    private static Array textArray(PreparedStatement ps, Set<WebhookEventType> eventTypes) throws SQLException {
        String[] names = eventTypes.stream().map(Enum::name).toArray(String[]::new);
        return ps.getConnection().createArrayOf("text", names);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new IllegalStateException(column + " is null");
        }
        return value.toInstant();
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
