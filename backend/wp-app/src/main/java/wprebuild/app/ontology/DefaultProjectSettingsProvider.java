package wprebuild.app.ontology;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.crud.NewEntitySettings;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.shortform.DisplayNameSetting;

/**
 * Settings used when a project has no stored row, and by tests that do not open a database.
 *
 * <p>An empty display-name list selects the kernel default order (BS-09). New entities use the
 * legacy fallback prefix and the first ontology key. The running application uses
 * {@link StoredProjectSettingsProvider}.
 */
public final class DefaultProjectSettingsProvider implements ProjectSettingsProvider {

    /** Same fallback the previous editor used when a project had no prefix yet. */
    static final String DEFAULT_IRI_PREFIX = "http://www.example.org/";

    private static final OntologyKey DEFAULT_TARGET = OntologyKey.of(1);

    @Override
    public Map<String, String> prefixes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return Map.of();
    }

    @Override
    public List<DisplayNameSetting> displayNames(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return List.of();
    }

    @Override
    public NewEntitySettings newEntitySettings(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return NewEntitySettings.defaults(DEFAULT_IRI_PREFIX, DEFAULT_TARGET);
    }
}
