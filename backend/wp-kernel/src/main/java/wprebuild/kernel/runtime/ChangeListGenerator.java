package wprebuild.kernel.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.change.OntologyChange;

/**
 * Builds the change list for one write from the project state under the generation lock.
 *
 * @param <R> value returned to the caller after temporary IRIs in the result have been renamed
 */
@FunctionalInterface
public interface ChangeListGenerator<R> {

    GeneratedChanges<R> generate(ChangeContext context);

    /** Description stored on the revision. {@code n} is the number of effective changes. */
    default String describe(GeneratedChanges<R> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        return "Edited " + effective.size() + " axioms";
    }

    /** Replaces temporary IRIs in {@code result}. The default keeps {@code result}. */
    default R renameResult(R result, Map<IRI, IRI> renameMap) {
        Objects.requireNonNull(renameMap, "rename map");
        return result;
    }

    /** When {@code true}, listeners still run but should not emit high-level events. */
    default boolean silent() {
        return false;
    }
}
