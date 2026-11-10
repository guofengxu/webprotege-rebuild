package wprebuild.app.project;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.access.RoleAssignment;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class SetProjectMemberHandler implements CommandHandler<SetProjectMember, RoleAssignment> {

    private final AccessService access;

    SetProjectMemberHandler(AccessService access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public Class<SetProjectMember> commandType() {
        return SetProjectMember.class;
    }

    @Override
    public Optional<ProjectId> project(SetProjectMember command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(SetProjectMember command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_SHARING_SETTINGS);
    }

    @Override
    public RoleAssignment handle(SetProjectMember command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        access.assignRole(command.projectId(), command.userId(), command.role());
        return new RoleAssignment(command.projectId(), command.userId(), command.role());
    }
}
