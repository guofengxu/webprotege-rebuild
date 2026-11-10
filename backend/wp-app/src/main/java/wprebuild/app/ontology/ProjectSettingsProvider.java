package wprebuild.app.ontology;

import java.util.List;
import java.util.Map;
import wprebuild.kernel.crud.NewEntitySettings;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.shortform.DisplayNameSetting;

/**
 * Display-name and new-entity settings for one project.
 *
 * <p>P3-22 replaces the default implementation with values stored on the project.
 */
public interface ProjectSettingsProvider {

    /**
     * Custom prefix names. Built-in prefixes stay available when this map does not override them.
     */
    Map<String, String> prefixes(ProjectId projectId);

    /**
     * Display-name priority. An empty list selects the kernel default order (BS-09).
     */
    List<DisplayNameSetting> displayNames(ProjectId projectId);

    /** Rules used when a fresh entity IRI is minted. */
    NewEntitySettings newEntitySettings(ProjectId projectId);
}
