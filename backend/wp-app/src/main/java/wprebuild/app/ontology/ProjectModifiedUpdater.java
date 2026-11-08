package wprebuild.app.ontology;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import wprebuild.app.project.ProjectRepository;

/**
 * Records who last changed the project. Runs on the calling thread so the update joins the revision
 * transaction and still sees the new project row.
 */
@Component
public class ProjectModifiedUpdater {

    private static final Logger log = LoggerFactory.getLogger(ProjectModifiedUpdater.class);

    private final ProjectRepository projects;

    public ProjectModifiedUpdater(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @EventListener
    public void onRevisionCommitted(RevisionCommittedEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            // A failed update must not abort the revision transaction. PostgreSQL marks it failed
            // unless the statement ran inside a savepoint (WP-6 still logs the listener error).
            Object savepoint =
                    TransactionAspectSupport.currentTransactionStatus().createSavepoint();
            try {
                record(event);
                TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);
            } catch (RuntimeException failure) {
                TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
                throw failure;
            }
        } catch (NoTransactionException ex) {
            // The revision transaction already committed, or this call is outside a transactional proxy.
            record(event);
        }
    }

    private void record(RevisionCommittedEvent event) {
        projects.recordModification(
                event.projectId(), event.revision().user(), event.revision().timestamp());
        log.debug(
                "recorded modification of project {} at revision {}",
                event.projectId(),
                event.revision().number());
    }
}
