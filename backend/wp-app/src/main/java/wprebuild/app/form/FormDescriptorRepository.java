package wprebuild.app.form;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import wprebuild.kernel.id.ProjectId;

/** Persistence for {@code project_form} (BS-16). */
public interface FormDescriptorRepository {

    List<FormDescriptorRow> listByProject(ProjectId projectId);

    Optional<FormDescriptorRow> find(ProjectId projectId, UUID formId);

    /**
     * Upserts a descriptor. Existing rows keep their {@code ordinal}; new rows append after
     * {@code max(ordinal)}.
     */
    FormDescriptorRow save(ProjectId projectId, UUID formId, String descriptorJson);

    /**
     * Deletes the descriptor and its selector (BS-16 cascade). Returns {@code false} when no
     * descriptor existed.
     */
    boolean delete(ProjectId projectId, UUID formId);

    /**
     * Replaces every descriptor for the project, assigning ordinals {@code 0..n-1} in list order.
     * Selectors for form ids absent from {@code descriptors} are removed.
     */
    void replaceAll(ProjectId projectId, List<FormDescriptorWrite> descriptors);
}
