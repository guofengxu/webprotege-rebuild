package wprebuild.instances.app;

/** Request exceeds configured body or ops limits (→ 413). */
final class PayloadTooLargeException extends RuntimeException {

    PayloadTooLargeException(String message) {
        super(message);
    }
}
