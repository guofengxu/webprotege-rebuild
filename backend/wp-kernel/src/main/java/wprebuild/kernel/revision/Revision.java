package wprebuild.kernel.revision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

/**
 * One committed project revision. Numbering starts at 1 (BS-03); {@link RevisionNumber#ZERO} is not
 * a stored revision.
 */
public record Revision(
        RevisionNumber number, UserId user, Instant timestamp, String description, List<OntologyChange> changes) {

    public Revision {
        Objects.requireNonNull(number, "revision number");
        if (number.isZero()) {
            throw new IllegalArgumentException("revision number must be greater than 0");
        }
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(description, "description");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
}
