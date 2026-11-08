package wprebuild.app.bus;

import wprebuild.kernel.id.ProjectId;

/**
 * Whether a project can accept a command. The project module supplies the bean (P3-08); the bus does
 * not depend on that module.
 */
public interface ProjectExistenceCheck {

    ProjectState state(ProjectId projectId);

    enum ProjectState {
        ACTIVE,
        TRASHED,
        MISSING
    }
}
