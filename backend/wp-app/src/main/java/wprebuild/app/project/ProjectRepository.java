package wprebuild.app.project;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Project metadata. Ontology code uses this instead of JDBC. */
public interface ProjectRepository {

    void insert(ProjectDetails details);

    Optional<ProjectDetails> find(ProjectId projectId);

    /** Projects where {@code userId} has any role. */
    List<ProjectDetails> listFor(UserId userId, boolean trashed);

    /** Every project, including those with no role for the caller. System administrators use this. */
    List<ProjectDetails> listAll(boolean trashed);

    ProjectDetails updateDetails(
            ProjectId projectId, String displayName, String description, UserId modifiedBy, Instant modifiedAt);

    ProjectDetails setTrash(ProjectId projectId, boolean inTrash, UserId modifiedBy, Instant modifiedAt);

    /** Last editor, written when a revision commits. */
    void recordModification(ProjectId projectId, UserId modifiedBy, Instant modifiedAt);
}
