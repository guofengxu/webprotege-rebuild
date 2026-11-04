package wprebuild.kernel.runtime;

import java.util.List;
import java.util.Objects;
import wprebuild.kernel.change.OntologyChange;

/** Changes produced by one {@link ChangeListGenerator}, plus the value returned to the caller. */
public record GeneratedChanges<R>(List<OntologyChange> changes, R result) {

    public GeneratedChanges {
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        Objects.requireNonNull(result, "result");
    }
}
