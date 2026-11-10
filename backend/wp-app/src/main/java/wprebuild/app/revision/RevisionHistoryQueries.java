package wprebuild.app.revision;

import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.id.ProjectId;

/**
 * Read-only revision metadata. Implementations select columns from {@code revisions} and
 * {@code revision_subjects} and do not decode payloads.
 */
public interface RevisionHistoryQueries {

    /** Upper bound for a history page, matching other paged project reads. */
    int MAX_SIZE = 200;

    /** Revisions of one project, highest revision number first. */
    RevisionPage list(ProjectId projectId, int page, int size);

    /**
     * Revisions whose subject index contains {@code subject}, highest revision number first.
     *
     * <p>The lookup uses {@code revision_subjects} and does not scan every revision.
     */
    RevisionPage listBySubject(ProjectId projectId, IRI subject, int page, int size);
}
