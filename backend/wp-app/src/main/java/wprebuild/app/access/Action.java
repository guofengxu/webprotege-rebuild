package wprebuild.app.access;

/**
 * Actions named in BS-10. A project-scoped action is checked against one project; the others are
 * application actions.
 */
public enum Action {
    VIEW_PROJECT(true),
    VIEW_CHANGES(true),
    DOWNLOAD_PROJECT(true),
    EDIT_ONTOLOGY(true),
    EDIT_ONTOLOGY_ANNOTATIONS(true),
    CREATE_CLASS(true),
    DELETE_CLASS(true),
    CREATE_PROPERTY(true),
    DELETE_PROPERTY(true),
    CREATE_INDIVIDUAL(true),
    DELETE_INDIVIDUAL(true),
    REVERT_CHANGES(true),
    EDIT_PROJECT_SETTINGS(true),
    EDIT_SHARING_SETTINGS(true),
    EDIT_NEW_ENTITY_SETTINGS(true),
    EDIT_PROJECT_PREFIXES(true),
    /** BS-10: allowed only on a project where the user has {@link ProjectRole#CAN_MANAGE}. */
    MOVE_ANY_PROJECT_TO_TRASH(true),
    CREATE_EMPTY_PROJECT(false),
    UPLOAD_PROJECT(false),
    CREATE_ACCOUNT(false),
    DELETE_ANY_ACCOUNT(false),
    RESET_ANY_USER_PASSWORD(false),
    EDIT_APPLICATION_SETTINGS(false);

    private final boolean projectScoped;

    Action(boolean projectScoped) {
        this.projectScoped = projectScoped;
    }

    public boolean projectScoped() {
        return projectScoped;
    }
}
