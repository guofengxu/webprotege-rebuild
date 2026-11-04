package wprebuild.kernel.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.revision.Revision;

/**
 * Outcome of one {@link ChangePipeline#apply} call.
 *
 * @param <R> renamed generator result
 * @param revision empty when every change was a no-op (WP-4)
 */
public record ChangeApplicationResult<R>(
        R result, List<OntologyChange> effectiveChanges, Map<IRI, IRI> renameMap, Optional<Revision> revision) {

    public ChangeApplicationResult {
        Objects.requireNonNull(result, "result");
        effectiveChanges = List.copyOf(Objects.requireNonNull(effectiveChanges, "effective changes"));
        renameMap = Map.copyOf(Objects.requireNonNull(renameMap, "rename map"));
        Objects.requireNonNull(revision, "revision");
        // WP-4: a revision exists exactly when the effective list is non-empty, and it stores that list.
        if (effectiveChanges.isEmpty() != revision.isEmpty()) {
            throw new IllegalArgumentException("revision is present only when there are effective changes");
        }
        if (revision.isPresent() && !revision.orElseThrow().changes().equals(effectiveChanges)) {
            throw new IllegalArgumentException("revision changes must equal the effective changes");
        }
    }
}
