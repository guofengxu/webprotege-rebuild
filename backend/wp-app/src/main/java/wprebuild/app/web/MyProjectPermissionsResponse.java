package wprebuild.app.web;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import wprebuild.app.access.Action;
import wprebuild.app.access.ProjectAccess;
import wprebuild.app.access.ProjectRole;
import wprebuild.kernel.id.ProjectId;

/** Response for {@code GET /api/projects/{id}/permissions/me}. */
@Schema(name = "MyProjectPermissionsResponse")
public record MyProjectPermissionsResponse(
        String projectId,

        @Schema(
                nullable = true,
                description = "Assigned project role, or null when the caller has none",
                allowableValues = {"CAN_VIEW", "CAN_COMMENT", "CAN_EDIT", "CAN_MANAGE"})
        ProjectRole role,

        boolean systemAdmin,

        @ArraySchema(
                schema =
                        @Schema(
                                description = "Project-scoped action",
                                allowableValues = {
                                    "VIEW_PROJECT",
                                    "VIEW_CHANGES",
                                    "DOWNLOAD_PROJECT",
                                    "EDIT_ONTOLOGY",
                                    "EDIT_ONTOLOGY_ANNOTATIONS",
                                    "CREATE_CLASS",
                                    "DELETE_CLASS",
                                    "CREATE_PROPERTY",
                                    "DELETE_PROPERTY",
                                    "CREATE_INDIVIDUAL",
                                    "DELETE_INDIVIDUAL",
                                    "REVERT_CHANGES",
                                    "EDIT_PROJECT_SETTINGS",
                                    "EDIT_SHARING_SETTINGS",
                                    "EDIT_NEW_ENTITY_SETTINGS",
                                    "EDIT_PROJECT_PREFIXES",
                                    "MOVE_ANY_PROJECT_TO_TRASH"
                                }))
        List<Action> actions) {

    public MyProjectPermissionsResponse {
        Objects.requireNonNull(projectId, "project id");
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }

    public static MyProjectPermissionsResponse from(ProjectId projectId, ProjectAccess access) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(access, "access");
        List<Action> ordered = new ArrayList<>();
        for (Action action : Action.values()) {
            if (access.actions().contains(action)) {
                ordered.add(action);
            }
        }
        return new MyProjectPermissionsResponse(projectId.toString(), access.role(), access.systemAdmin(), ordered);
    }
}
