package wprebuild.app.project;

/**
 * Display name and description stored on {@code projects}. Callers at the HTTP boundary turn a
 * failed check into a 400 response.
 */
public final class ProjectText {

    private ProjectText() {}

    public static String displayName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        return name.strip();
    }

    public static String description(String description) {
        if (description == null) {
            return "";
        }
        return description;
    }
}
