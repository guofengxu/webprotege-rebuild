package wprebuild.app.projection;

/**
 * Version of the projection table shape that instance-store (and other consumers) depend on.
 *
 * <p>Bump {@link #VERSION} when a breaking change lands (dropped columns, changed column meaning,
 * changed {@code entity_type} enum values, etc.). Non-breaking additive columns need not bump it.
 */
public final class ProjectionContract {

    /**
     * Current contract version written into {@code projection.project_state.contract_version}.
     * Starts at {@code 1} with the V9 column (P6-55).
     */
    public static final int VERSION = 1;

    private ProjectionContract() {}
}
