package wprebuild.app.revision;

/**
 * Thrown by an {@link OutboxConsumer} when it cannot process the batch yet (for example the in-memory
 * model has not applied the revision). The relay rolls back, waits briefly, and retries without an
 * error log or exponential backoff.
 */
public final class OutboxRetryLater extends RuntimeException {

    public OutboxRetryLater() {
        super();
    }

    public OutboxRetryLater(String message) {
        super(message);
    }

    public OutboxRetryLater(String message, Throwable cause) {
        super(message, cause);
    }
}
