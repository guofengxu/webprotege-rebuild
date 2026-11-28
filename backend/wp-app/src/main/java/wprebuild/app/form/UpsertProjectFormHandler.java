package wprebuild.app.form;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

/** Handles {@link UpsertProjectForm}. Requires {@code EDIT_FORMS}. */
@Component
class UpsertProjectFormHandler implements CommandHandler<UpsertProjectForm, ProjectForm> {

    private final FormDescriptorRepository descriptors;
    private final FormSelectorRepository selectors;

    UpsertProjectFormHandler(FormDescriptorRepository descriptors, FormSelectorRepository selectors) {
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.selectors = Objects.requireNonNull(selectors, "selectors");
    }

    @Override
    public Class<UpsertProjectForm> commandType() {
        return UpsertProjectForm.class;
    }

    @Override
    public Optional<ProjectId> project(UpsertProjectForm command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(UpsertProjectForm command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_FORMS);
    }

    @Override
    @Transactional
    public ProjectForm handle(UpsertProjectForm command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        FormDescriptorRow saved = descriptors.save(command.projectId(), command.formId(), command.descriptorJson());
        String criteriaJson = command.criteriaJson();
        if (criteriaJson == null) {
            selectors.delete(command.projectId(), command.formId());
        } else {
            selectors.save(command.projectId(), command.formId(), criteriaJson);
        }
        return new ProjectForm(
                saved.projectId(), saved.formId(), saved.ordinal(), saved.descriptorJson(), criteriaJson);
    }
}
