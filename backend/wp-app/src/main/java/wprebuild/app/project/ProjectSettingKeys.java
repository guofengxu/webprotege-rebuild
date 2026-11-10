package wprebuild.app.project;

import java.util.Objects;
import java.util.Set;
import wprebuild.app.access.Action;

/** The three {@code project_settings} keys (P3-22). */
public final class ProjectSettingKeys {

    public static final String PREFIXES = "prefixes";

    public static final String NEW_ENTITY = "new-entity";

    public static final String DISPLAY_NAMES = "display-names";

    private static final Set<String> ALL = Set.of(PREFIXES, NEW_ENTITY, DISPLAY_NAMES);

    private ProjectSettingKeys() {}

    public static String check(String key) {
        if (!ALL.contains(Objects.requireNonNull(key, "key"))) {
            throw new IllegalArgumentException("unknown project setting");
        }
        return key;
    }

    /** Permission required to replace one key. Reading always uses {@code VIEW_PROJECT}. */
    public static Action editAction(String key) {
        return switch (check(key)) {
            case PREFIXES -> Action.EDIT_PROJECT_PREFIXES;
            case NEW_ENTITY -> Action.EDIT_NEW_ENTITY_SETTINGS;
            case DISPLAY_NAMES -> Action.EDIT_PROJECT_SETTINGS;
            default -> throw new IllegalStateException("unknown project setting");
        };
    }
}
