package wprebuild.app.revision;

import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;

/** Writes the outbox row in the same transaction as the revision (ADR-004). */
interface RevisionCommitOutbox {

    void record(ProjectId projectId, Revision revision);
}
