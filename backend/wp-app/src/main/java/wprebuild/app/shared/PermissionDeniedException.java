package wprebuild.app.shared;

/** The caller is not allowed to perform the action (BS-10). */
public final class PermissionDeniedException extends CodedException {

    public static final String CODE = "PERMISSION_DENIED";

    public PermissionDeniedException(String message) {
        super(CODE, message);
    }
}
