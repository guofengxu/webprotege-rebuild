package wprebuild.app.project;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.RoleAssignment;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class GetProjectMembersHandler implements CommandHandler<GetProjectMembers, List<RoleAssignment>> {

    private final JdbcRoleAssignmentRepository assignments;

    GetProjectMembersHandler(JdbcRoleAssignmentRepository assignments) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    @Override
    public Class<GetProjectMembers> commandType() {
        return GetProjectMembers.class;
    }

    @Override
    public Optional<ProjectId> project(GetProjectMembers command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetProjectMembers command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_SHARING_SETTINGS);
    }

    @Override
    public List<RoleAssignment> handle(GetProjectMembers command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return assignments.list(command.projectId());
    }
}
