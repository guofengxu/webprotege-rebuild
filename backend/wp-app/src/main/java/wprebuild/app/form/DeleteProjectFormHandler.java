package wprebuild.app.form;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

/** Handles {@link DeleteProjectForm}. Requires {@code EDIT_FORMS}. */
@Component
class DeleteProjectFormHandler implements CommandHandler<DeleteProjectForm, Void> {

    private final FormDescriptorRepository descriptors;

    DeleteProjectFormHandler(FormDescriptorRepository descriptors) {
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
    }

    @Override
    public Class<DeleteProjectForm> commandType() {
        return DeleteProjectForm.class;
    }

    @Override
    public Optional<ProjectId> project(DeleteProjectForm command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(DeleteProjectForm command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_FORMS);
    }

    @Override
    public Void handle(DeleteProjectForm command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        if (!descriptors.delete(command.projectId(), command.formId())) {
            throw new NotFoundException("form not found");
        }
        return null;
    }
}
