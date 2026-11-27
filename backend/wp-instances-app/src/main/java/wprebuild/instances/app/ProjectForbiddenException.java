package wprebuild.instances.app;

import java.util.UUID;

/** Path {@code projectId} does not match the configured single-tenant project (→ 403). */
final class ProjectForbiddenException extends RuntimeException {

    private final UUID projectId;

    ProjectForbiddenException(UUID projectId) {
        super("project " + projectId + " is not the configured tenant");
        this.projectId = projectId;
    }

    UUID projectId() {
        return projectId;
    }
}
