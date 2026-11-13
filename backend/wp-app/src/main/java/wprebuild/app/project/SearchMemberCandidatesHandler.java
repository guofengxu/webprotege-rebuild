package wprebuild.app.project;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.access.UserService;
import wprebuild.app.access.UserSummary;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class SearchMemberCandidatesHandler implements CommandHandler<SearchMemberCandidates, List<UserSummary>> {

    private final UserService users;

    SearchMemberCandidatesHandler(UserService users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public Class<SearchMemberCandidates> commandType() {
        return SearchMemberCandidates.class;
    }

    @Override
    public Optional<ProjectId> project(SearchMemberCandidates command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(SearchMemberCandidates command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_SHARING_SETTINGS);
    }

    @Override
    public List<UserSummary> handle(SearchMemberCandidates command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return users.search(command.query(), command.limit());
    }
}
