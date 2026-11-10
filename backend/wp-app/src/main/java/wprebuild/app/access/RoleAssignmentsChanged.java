package wprebuild.app.access;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;

/**
 * Published by {@link AccessService} when sharing changes a project's role assignments (P3-22).
 *
 * <p>The events module turns this into {@code PermissionsChanged}. This record is the contract both
 * sides share; P3-22 publishes it and does not redefine it.
 */
public record RoleAssignmentsChanged(ProjectId projectId) {

    public RoleAssignmentsChanged {
        Objects.requireNonNull(projectId, "project id");
    }
}
