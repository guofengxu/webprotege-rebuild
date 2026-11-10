package wprebuild.app.events;

/**
 * Tells the client the ring buffer no longer contains the revisions after its cursor (BS-11).
 *
 * <p>This is not a {@link ProjectEvent}. The client should reload the project.
 */
public record ResyncSignal(String type) {

    public static final ResyncSignal INSTANCE = new ResyncSignal("resync");

    public ResyncSignal {
        if (!"resync".equals(type)) {
            throw new IllegalArgumentException("type must be resync");
        }
    }
}
