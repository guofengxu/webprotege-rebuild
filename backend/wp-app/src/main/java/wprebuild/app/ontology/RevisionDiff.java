package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;

/** A limited slice of one revision's changes, already grouped so each subject stays together. */
public record RevisionDiff(long revNo, List<RevisionChange> changes, int limit, long total) {

    public RevisionDiff {
        if (revNo < 1) {
            throw new IllegalArgumentException("revision number must be greater than 0");
        }
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (total < changes.size()) {
            throw new IllegalArgumentException("total is smaller than the returned changes");
        }
    }
}
