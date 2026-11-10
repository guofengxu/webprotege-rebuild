package wprebuild.app.access;

/** A refresh token that rotation already replaced was presented again. The device session is revoked. */
public final class RefreshTokenReusedException extends RuntimeException {

    public static final String CODE = "REFRESH_TOKEN_REUSED";

    public RefreshTokenReusedException() {
        super("refresh token was reused");
    }
}
