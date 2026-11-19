package wprebuild.kernel.runtime;

import java.util.Objects;
import wprebuild.kernel.id.RevisionNumber;

/**
 * The project's head revision no longer matches the value supplied to {@link ChangePipeline#apply}.
 *
 * <p>Raised under the generation lock before the change list is generated, so no revision is written.
 */
public final class StaleHeadException extends RuntimeException {

    private final RevisionNumber expected;

    private final RevisionNumber actual;

    public StaleHeadException(RevisionNumber expected, RevisionNumber actual) {
        super("expected head " + Objects.requireNonNull(expected, "expected") + " but was "
                + Objects.requireNonNull(actual, "actual"));
        this.expected = expected;
        this.actual = actual;
    }

    public RevisionNumber expected() {
        return expected;
    }

    public RevisionNumber actual() {
        return actual;
    }
}
