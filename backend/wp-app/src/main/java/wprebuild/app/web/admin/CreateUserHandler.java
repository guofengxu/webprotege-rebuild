package wprebuild.app.web.admin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserProfile;
import wprebuild.app.access.UserService;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Service
public final class CreateUserHandler implements CommandHandler<CreateUser, UserProfile> {

    private static final Logger log = LoggerFactory.getLogger(CreateUserHandler.class);

    private final UserService users;

    public CreateUserHandler(UserService users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public Class<CreateUser> commandType() {
        return CreateUser.class;
    }

    @Override
    public Optional<ProjectId> project(CreateUser command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(CreateUser command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.CREATE_ACCOUNT);
    }

    @Override
    public UserProfile handle(CreateUser command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        UserAccount account = users.create(
                command.userId(), command.displayName(), command.email(), command.initialPassword(), List.of());
        log.info("created user {} by {}", AccountText.forLog(command.userId()), AccountText.caller(context));
        return UserProfile.from(account);
    }
}
