package wprebuild.app.web.admin;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.access.UserPage;
import wprebuild.app.access.UserService;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Service
public final class ListUsersHandler implements CommandHandler<ListUsers, UserPage> {

    private final UserService users;

    public ListUsersHandler(UserService users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public Class<ListUsers> commandType() {
        return ListUsers.class;
    }

    @Override
    public Optional<ProjectId> project(ListUsers command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(ListUsers command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.CREATE_ACCOUNT);
    }

    @Override
    public UserPage handle(ListUsers command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return users.list(command.page(), command.size());
    }
}
