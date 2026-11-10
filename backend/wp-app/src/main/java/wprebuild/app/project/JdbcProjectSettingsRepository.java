package wprebuild.app.project;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.ProjectId;

/** {@code project_settings}. Values are JSON documents keyed by {@link ProjectSettingKeys}. */
@Repository
public class JdbcProjectSettingsRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcProjectSettingsRepository.class);

    private final JdbcClient jdbc;

    private final ApplicationEventPublisher events;

    public JdbcProjectSettingsRepository(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.events = Objects.requireNonNull(events, "events");
    }

    public Optional<String> find(ProjectId projectId, String key) {
        Objects.requireNonNull(projectId, "project id");
        ProjectSettingKeys.check(key);
        return jdbc.sql("""
                        select value::text as value
                        from project_settings
                        where project_id = ? and key = ?
                        """)
                .param(projectId.value())
                .param(key)
                .query((rs, row) -> rs.getString("value"))
                .optional();
    }

    /**
     * Inserts or replaces one document. An equal JSON value does not publish
     * {@link ProjectSettingsChanged}.
     */
    public void save(ProjectId projectId, String key, String json) {
        Objects.requireNonNull(projectId, "project id");
        ProjectSettingKeys.check(key);
        Objects.requireNonNull(json, "json");
        Optional<String> existing = find(projectId, key);
        if (existing.isPresent() && ProjectSettingsDocuments.same(existing.get(), json)) {
            return;
        }
        int stored =
                jdbc.sql("""
                        insert into project_settings (project_id, key, value)
                        values (?, ?, cast(? as jsonb))
                        on conflict (project_id, key) do update set value = excluded.value
                        """).param(projectId.value()).param(key).param(json).update();
        if (stored != 1) {
            throw new IllegalStateException("project setting was not stored");
        }
        log.info("project {} setting {} updated", projectId, key);
        events.publishEvent(new ProjectSettingsChanged(projectId, key));
    }
}
