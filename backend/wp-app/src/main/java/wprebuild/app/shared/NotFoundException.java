package wprebuild.app.shared;

/** The requested object does not exist. */
public final class NotFoundException extends CodedException {

    public static final String CODE = "NOT_FOUND";

    public NotFoundException(String message) {
        super(CODE, message);
    }
}
