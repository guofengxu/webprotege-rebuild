package wprebuild.app.access;

import java.util.Objects;

/**
 * Application roles stored in {@code users.app_roles}. {@link #SYSTEM_ADMIN} may perform every
 * application action; project checks treat that role as {@link ProjectRole#CAN_MANAGE}.
 */
public enum AppRole {
    PROJECT_CREATOR,
    SYSTEM_ADMIN;

    public boolean allows(Action action) {
        Objects.requireNonNull(action, "action");
        if (action.projectScoped()) {
            return false;
        }
        return switch (this) {
            case SYSTEM_ADMIN -> true;
            case PROJECT_CREATOR -> action == Action.CREATE_EMPTY_PROJECT || action == Action.UPLOAD_PROJECT;
        };
    }
}
