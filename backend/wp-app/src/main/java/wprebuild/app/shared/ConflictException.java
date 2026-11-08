package wprebuild.app.shared;

/** The request conflicts with the current state. {@code code} distinguishes the conflict. */
public final class ConflictException extends CodedException {

    public static final String CODE = "CONFLICT";

    public ConflictException(String message) {
        this(CODE, message);
    }

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
