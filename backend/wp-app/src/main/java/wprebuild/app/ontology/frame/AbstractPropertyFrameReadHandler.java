package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Optional;
import wprebuild.app.access.Action;
import wprebuild.app.bus.Command;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

/** Loads one property frame. Permission is checked by the command bus. */
abstract class AbstractPropertyFrameReadHandler<C extends Command<F>, F> implements CommandHandler<C, F> {

    @Override
    public Optional<ProjectId> project(C command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(projectId(command));
    }

    @Override
    public Optional<Action> requiredAction(C command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public F handle(C command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return read(command);
    }

    protected abstract ProjectId projectId(C command);

    protected abstract F read(C command);
}
