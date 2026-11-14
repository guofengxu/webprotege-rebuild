package wprebuild.app.events;

import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import wprebuild.app.project.ProjectTrashStateChanged;

/**
 * When a project moves to the trash, asks clients to reload and then closes every subscription.
 */
@Component
final class ProjectTrashListener {

    private final ProjectEventHub hub;

    ProjectTrashListener(ProjectEventHub hub) {
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    @EventListener
    public void onTrashState(ProjectTrashStateChanged event) {
        Objects.requireNonNull(event, "event");
        if (!event.trashed()) {
            return;
        }
        hub.resync(event.projectId());
        hub.disconnectAll(event.projectId());
    }
}
