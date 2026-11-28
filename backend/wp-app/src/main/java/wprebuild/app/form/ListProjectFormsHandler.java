package wprebuild.app.form;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

/** Handles {@link ListProjectForms}. Requires {@code EDIT_FORMS}. */
@Component
class ListProjectFormsHandler implements CommandHandler<ListProjectForms, List<ProjectForm>> {

    private final FormDescriptorRepository descriptors;
    private final FormSelectorRepository selectors;

    ListProjectFormsHandler(FormDescriptorRepository descriptors, FormSelectorRepository selectors) {
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.selectors = Objects.requireNonNull(selectors, "selectors");
    }

    @Override
    public Class<ListProjectForms> commandType() {
        return ListProjectForms.class;
    }

    @Override
    public Optional<ProjectId> project(ListProjectForms command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(ListProjectForms command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_FORMS);
    }

    @Override
    public List<ProjectForm> handle(ListProjectForms command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        Map<UUID, String> criteriaByForm = new HashMap<>();
        for (FormSelectorRow row : selectors.listByProject(command.projectId())) {
            criteriaByForm.put(row.formId(), row.criteriaJson());
        }
        List<ProjectForm> forms = new ArrayList<>();
        for (FormDescriptorRow row : descriptors.listByProject(command.projectId())) {
            forms.add(new ProjectForm(
                    row.projectId(),
                    row.formId(),
                    row.ordinal(),
                    row.descriptorJson(),
                    criteriaByForm.get(row.formId())));
        }
        return List.copyOf(forms);
    }
}
