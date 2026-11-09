package wprebuild.app.ontology;

import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import wprebuild.app.access.AccessService;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.project.ProjectRepository;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Inserts a project row and grants the creator {@code CAN_MANAGE}. The caller submits revision 1 in
 * the same transaction. A rollback drops a runtime loaded for that new id.
 */
@Component
public class ProjectProvisioning {

    private final ProjectRepository projects;

    private final AccessService access;

    private final ProjectRuntimeRegistry registry;

    public ProjectProvisioning(ProjectRepository projects, AccessService access, ProjectRuntimeRegistry registry) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.access = Objects.requireNonNull(access, "access");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public ProjectId insert(String name, String description, UserId user) {
        Objects.requireNonNull(user, "user");
        ProjectId projectId = ProjectId.random();
        evictUnlessCommitted(projectId);
        Instant now = Instant.now();
        projects.insert(new ProjectDetails(projectId, name, description, user, false, now, user, now, user));
        access.grantCreator(projectId, user);
        return projectId;
    }

    private void evictUnlessCommitted(ProjectId projectId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    registry.evict(projectId);
                }
            }
        });
    }
}
