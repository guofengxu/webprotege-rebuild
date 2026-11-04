package wprebuild.kernel.generator;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of trying to revert one revision (BS-04).
 *
 * <p>A rejection carries a reason and no changes. A success carries no reason; the pipeline may
 * still drop inverse changes that no longer apply (BS-02).
 */
public record RevertResult(boolean reverted, Optional<String> reason) {

    public RevertResult {
        Objects.requireNonNull(reason, "reason");
        if (reverted && reason.isPresent()) {
            throw new IllegalArgumentException("a successful revert has no reason");
        }
        if (!reverted && (reason.isEmpty() || reason.get().isBlank())) {
            throw new IllegalArgumentException("a rejected revert requires a reason");
        }
    }
}
