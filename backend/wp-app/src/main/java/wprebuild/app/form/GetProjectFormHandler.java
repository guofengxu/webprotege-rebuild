package wprebuild.app.form;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

/** Handles {@link GetProjectForm}. Requires {@code EDIT_FORMS}. */
@Component
class GetProjectFormHandler implements CommandHandler<GetProjectForm, ProjectForm> {

    private final FormDescriptorRepository descriptors;
    private final FormSelectorRepository selectors;

    GetProjectFormHandler(FormDescriptorRepository descriptors, FormSelectorRepository selectors) {
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.selectors = Objects.requireNonNull(selectors, "selectors");
    }

    @Override
    public Class<GetProjectForm> commandType() {
        return GetProjectForm.class;
    }

    @Override
    public Optional<ProjectId> project(GetProjectForm command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetProjectForm command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_FORMS);
    }

    @Override
    public ProjectForm handle(GetProjectForm command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        FormDescriptorRow row = descriptors
                .find(command.projectId(), command.formId())
                .orElseThrow(() -> new NotFoundException("form not found"));
        String criteria = selectors
                .find(command.projectId(), command.formId())
                .map(FormSelectorRow::criteriaJson)
                .orElse(null);
        return new ProjectForm(row.projectId(), row.formId(), row.ordinal(), row.descriptorJson(), criteria);
    }
}
