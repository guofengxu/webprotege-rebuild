package wprebuild.instances.rules;

import java.util.UUID;

/**
 * Projection table shape version expected by the instance store when reading rules.
 *
 * <p>Must stay aligned with the editor's {@code ProjectionContract.VERSION} written into {@code
 * projection.project_state.contract_version}. Bump both when a breaking projection change lands.
 */
public final class ProjectionContract {

    /**
     * Expected {@code project_state.contract_version}. Starts at {@code 1} with the V9 column
     * (P6-55 / P6-56).
     */
    public static final int EXPECTED_VERSION = 1;

    private ProjectionContract() {}

    static IllegalStateException contractMismatch(UUID projectId, int actual) {
        return new IllegalStateException("projection contract_version mismatch for project "
                + projectId
                + ": expected "
                + EXPECTED_VERSION
                + ", actual "
                + actual);
    }
}
