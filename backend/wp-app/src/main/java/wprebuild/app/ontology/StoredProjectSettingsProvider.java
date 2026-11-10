package wprebuild.app.ontology;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import wprebuild.app.project.JdbcProjectSettingsRepository;
import wprebuild.app.project.ProjectSettingKeys;
import wprebuild.app.project.ProjectSettingsDocuments;
import wprebuild.kernel.crud.NewEntitySettings;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.shortform.DisplayNameSetting;

/** Reads {@code project_settings}. A missing row uses the same defaults as an unconfigured project. */
@Component
public final class StoredProjectSettingsProvider implements ProjectSettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(StoredProjectSettingsProvider.class);

    private final JdbcProjectSettingsRepository settings;

    public StoredProjectSettingsProvider(JdbcProjectSettingsRepository settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Map<String, String> prefixes(ProjectId projectId) {
        return read(projectId, ProjectSettingKeys.PREFIXES, ProjectSettingsDocuments::readPrefixes, Map.of());
    }

    @Override
    public List<DisplayNameSetting> displayNames(ProjectId projectId) {
        return read(projectId, ProjectSettingKeys.DISPLAY_NAMES, ProjectSettingsDocuments::readDisplayNames, List.of());
    }

    @Override
    public NewEntitySettings newEntitySettings(ProjectId projectId) {
        return read(
                projectId,
                ProjectSettingKeys.NEW_ENTITY,
                ProjectSettingsDocuments::readNewEntity,
                ProjectSettingsDocuments.defaultNewEntitySettings());
    }

    private <T> T read(ProjectId projectId, String key, Function<String, T> parser, T fallback) {
        Objects.requireNonNull(projectId, "project id");
        Optional<String> stored = settings.find(projectId, key);
        if (stored.isEmpty()) {
            return fallback;
        }
        try {
            return parser.apply(stored.get());
        } catch (RuntimeException ex) {
            log.error("project {} setting {} cannot be read", projectId, key, ex);
            throw new IllegalStateException("project setting " + key + " cannot be read", ex);
        }
    }
}
