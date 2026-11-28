package wprebuild.app.form;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

/** Handles {@link AllocateFormId}. Requires {@code EDIT_FORMS}. */
@Component
class AllocateFormIdHandler implements CommandHandler<AllocateFormId, UUID> {

    @Override
    public Class<AllocateFormId> commandType() {
        return AllocateFormId.class;
    }

    @Override
    public Optional<ProjectId> project(AllocateFormId command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(AllocateFormId command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_FORMS);
    }

    @Override
    public UUID handle(AllocateFormId command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return UUID.randomUUID();
    }
}
