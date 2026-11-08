package wprebuild.app.ontology;

import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.PostCommitListener;
import wprebuild.kernel.runtime.ProjectRuntime;

/** Forwards a committed revision to Spring listeners on the calling thread. */
final class RevisionCommittedPublisher implements PostCommitListener {

    private final ProjectId projectId;

    private final ApplicationEventPublisher events;

    RevisionCommittedPublisher(ProjectId projectId, ApplicationEventPublisher events) {
        this.projectId = Objects.requireNonNull(projectId, "project id");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void onCommitted(ProjectRuntime runtime, Revision revision, boolean silent) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(revision, "revision");
        events.publishEvent(new RevisionCommittedEvent(projectId, revision, silent));
    }
}
