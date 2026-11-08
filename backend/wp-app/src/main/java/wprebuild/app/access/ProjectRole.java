package wprebuild.app.access;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** BS-10 project roles. Each role includes every action of the role declared before it. */
public enum ProjectRole {
    CAN_VIEW(Action.VIEW_PROJECT, Action.VIEW_CHANGES, Action.DOWNLOAD_PROJECT),
    CAN_COMMENT(CAN_VIEW),
    CAN_EDIT(
            CAN_COMMENT,
            Action.EDIT_ONTOLOGY,
            Action.EDIT_ONTOLOGY_ANNOTATIONS,
            Action.CREATE_CLASS,
            Action.DELETE_CLASS,
            Action.CREATE_PROPERTY,
            Action.DELETE_PROPERTY,
            Action.CREATE_INDIVIDUAL,
            Action.DELETE_INDIVIDUAL,
            Action.REVERT_CHANGES),
    CAN_MANAGE(
            CAN_EDIT,
            Action.EDIT_PROJECT_SETTINGS,
            Action.EDIT_SHARING_SETTINGS,
            Action.EDIT_NEW_ENTITY_SETTINGS,
            Action.EDIT_PROJECT_PREFIXES,
            Action.MOVE_ANY_PROJECT_TO_TRASH);

    private final Set<Action> actions;

    ProjectRole(Action... added) {
        this.actions = Set.copyOf(List.of(added));
    }

    ProjectRole(ProjectRole parent, Action... added) {
        EnumSet<Action> all = EnumSet.copyOf(parent.actions);
        all.addAll(List.of(added));
        this.actions = Set.copyOf(all);
    }

    public boolean allows(Action action) {
        return actions.contains(Objects.requireNonNull(action, "action"));
    }

    public Set<Action> actions() {
        return actions;
    }
}
