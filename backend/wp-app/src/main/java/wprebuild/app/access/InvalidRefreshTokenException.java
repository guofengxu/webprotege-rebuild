package wprebuild.app.access;

/** The refresh token is missing, malformed, expired, revoked, or unknown. */
public final class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("refresh token is invalid");
    }
}
