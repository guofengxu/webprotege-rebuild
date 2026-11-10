package wprebuild.app.web.admin;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.access.UserService;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Service
public final class ResetUserPasswordHandler implements CommandHandler<ResetUserPassword, Void> {

    private static final Logger log = LoggerFactory.getLogger(ResetUserPasswordHandler.class);

    private final UserService users;

    public ResetUserPasswordHandler(UserService users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public Class<ResetUserPassword> commandType() {
        return ResetUserPassword.class;
    }

    @Override
    public Optional<ProjectId> project(ResetUserPassword command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(ResetUserPassword command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.RESET_ANY_USER_PASSWORD);
    }

    @Override
    public Void handle(ResetUserPassword command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        users.setPassword(command.userId(), command.newPassword());
        log.info(
                "reset password for {} by {}",
                AccountText.forLog(command.userId().value()),
                AccountText.caller(context));
        return null;
    }
}
