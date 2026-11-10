package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/** Reverts one revision as a new revision (BS-04). Requires {@code REVERT_CHANGES}. */
public record RevertRevision(ProjectId projectId, RevisionNumber revision) implements Command<RevisionNumber> {

    public RevertRevision {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(revision, "revision");
        if (revision.isZero()) {
            throw new IllegalArgumentException("revision number must be greater than 0");
        }
    }
}
