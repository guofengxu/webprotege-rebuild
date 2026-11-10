package wprebuild.app.project;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import wprebuild.kernel.id.ProjectId;

/**
 * Reads and replaces one project setting. The write publishes {@link ProjectSettingsChanged} inside
 * the transaction when the stored JSON changes.
 */
@Component
class ProjectSettingsStore {

    private final JdbcProjectSettingsRepository settings;

    ProjectSettingsStore(JdbcProjectSettingsRepository settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    JsonNode read(ProjectId projectId, String key) {
        ProjectSettingKeys.check(key);
        Optional<String> stored = settings.find(projectId, key);
        if (stored.isEmpty()) {
            return ProjectSettingsDocuments.defaults(key);
        }
        try {
            return ProjectSettingsDocuments.canonicalTree(key, stored.get());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("stored project setting " + key + " cannot be read", ex);
        }
    }

    @Transactional
    public JsonNode write(ProjectId projectId, String key, JsonNode document) {
        String json = ProjectSettingsDocuments.canonical(key, document);
        settings.save(projectId, key, json);
        return ProjectSettingsDocuments.canonicalTree(key, json);
    }
}
