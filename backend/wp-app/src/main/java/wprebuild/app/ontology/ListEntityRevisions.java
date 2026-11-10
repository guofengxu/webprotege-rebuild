package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.revision.RevisionHistoryQueries;
import wprebuild.app.revision.RevisionPage;
import wprebuild.kernel.id.ProjectId;

/**
 * Lists revisions that mention one entity, newest first. Requires {@code VIEW_CHANGES}.
 * {@code page} is zero-based.
 */
public record ListEntityRevisions(ProjectId projectId, IRI iri, int page, int size) implements Command<RevisionPage> {

    public ListEntityRevisions {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1 || size > RevisionHistoryQueries.MAX_SIZE) {
            throw new IllegalArgumentException("size must be from 1 to " + RevisionHistoryQueries.MAX_SIZE);
        }
    }
}
