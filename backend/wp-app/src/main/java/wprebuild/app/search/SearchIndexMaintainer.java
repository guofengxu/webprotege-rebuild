package wprebuild.app.search;

import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.RevisionCommittedEvent;

/**
 * Brings one project index up to the revision that just committed.
 *
 * <p>The publisher calls listeners on the writing thread, so a newly created entity is searchable
 * before that command returns.
 */
@Component
public final class SearchIndexMaintainer {

    private final ProjectSearchIndex index;

    public SearchIndexMaintainer(ProjectSearchIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    @EventListener
    public void onRevision(RevisionCommittedEvent event) {
        Objects.requireNonNull(event, "event");
        index.onCommitted(event.projectId(), event.revision());
    }
}
