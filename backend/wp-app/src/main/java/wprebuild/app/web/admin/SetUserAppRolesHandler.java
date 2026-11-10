package wprebuild.app.web.admin;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.access.UserProfile;
import wprebuild.app.access.UserService;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Service
public final class SetUserAppRolesHandler implements CommandHandler<SetUserAppRoles, UserProfile> {

    private static final Logger log = LoggerFactory.getLogger(SetUserAppRolesHandler.class);

    private final UserService users;

    public SetUserAppRolesHandler(UserService users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public Class<SetUserAppRoles> commandType() {
        return SetUserAppRoles.class;
    }

    @Override
    public Optional<ProjectId> project(SetUserAppRoles command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(SetUserAppRoles command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_APPLICATION_SETTINGS);
    }

    @Override
    public UserProfile handle(SetUserAppRoles command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        UserProfile updated = users.setAppRoles(command.userId(), command.appRoles());
        log.info(
                "updated app roles for {} by {}",
                AccountText.forLog(command.userId().value()),
                AccountText.caller(context));
        return updated;
    }
}
