package wprebuild.app.project;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class RemoveProjectMemberHandler implements CommandHandler<RemoveProjectMember, Void> {

    private final AccessService access;

    RemoveProjectMemberHandler(AccessService access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public Class<RemoveProjectMember> commandType() {
        return RemoveProjectMember.class;
    }

    @Override
    public Optional<ProjectId> project(RemoveProjectMember command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(RemoveProjectMember command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_SHARING_SETTINGS);
    }

    @Override
    public Void handle(RemoveProjectMember command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        access.removeRole(command.projectId(), command.userId());
        return null;
    }
}
