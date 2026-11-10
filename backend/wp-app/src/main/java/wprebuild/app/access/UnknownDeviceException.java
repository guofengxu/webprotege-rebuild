package wprebuild.app.access;

/** {@link AccessTokenService#revoke} found no active device owned by that user. */
public final class UnknownDeviceException extends RuntimeException {

    public UnknownDeviceException() {
        super("device not found");
    }
}
