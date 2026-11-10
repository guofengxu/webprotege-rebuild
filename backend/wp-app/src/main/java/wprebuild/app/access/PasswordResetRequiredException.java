package wprebuild.app.access;

/** The account must set a password before a refresh token may be rotated. */
public final class PasswordResetRequiredException extends RuntimeException {

    public PasswordResetRequiredException() {
        super("password reset is required");
    }
}
