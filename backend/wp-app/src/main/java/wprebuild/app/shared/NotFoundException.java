package wprebuild.app.shared;

/** The requested object does not exist. {@code code} distinguishes the missing resource. */
public final class NotFoundException extends CodedException {

    public static final String CODE = "NOT_FOUND";

    /** Staged upload missing, expired, or not owned by the caller (P6-50). */
    public static final String UPLOAD_NOT_FOUND = "UPLOAD_NOT_FOUND";

    public NotFoundException(String message) {
        this(CODE, message);
    }

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
