package wprebuild.app.form;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import wprebuild.kernel.id.ProjectId;

/** Persistence for {@code project_form_selector} (BS-16). */
public interface FormSelectorRepository {

    List<FormSelectorRow> listByProject(ProjectId projectId);

    Optional<FormSelectorRow> find(ProjectId projectId, UUID formId);

    FormSelectorRow save(ProjectId projectId, UUID formId, String criteriaJson);

    boolean delete(ProjectId projectId, UUID formId);
}
