package wprebuild.app.project;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.access.ProjectAccess;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class GetMyProjectPermissionsHandler implements CommandHandler<GetMyProjectPermissions, ProjectAccess> {

    private final AccessService access;

    GetMyProjectPermissionsHandler(AccessService access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public Class<GetMyProjectPermissions> commandType() {
        return GetMyProjectPermissions.class;
    }

    @Override
    public Optional<ProjectId> project(GetMyProjectPermissions command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetMyProjectPermissions command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public ProjectAccess handle(GetMyProjectPermissions command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return access.projectAccess(context.user(), command.projectId());
    }
}
