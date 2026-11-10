package wprebuild.app.events;

import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.ProjectResyncRequested;

/** Turns a display-language rebuild into a client {@code resync}. */
@Component
final class ProjectResyncListener {

    private final ProjectEventHub hub;

    ProjectResyncListener(ProjectEventHub hub) {
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    @EventListener
    public void onResync(ProjectResyncRequested event) {
        hub.resync(event.projectId());
    }
}
