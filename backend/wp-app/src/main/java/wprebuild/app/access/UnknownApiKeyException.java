package wprebuild.app.access;

/** {@link ApiKeyService#revoke} found no key owned by that user. */
public final class UnknownApiKeyException extends RuntimeException {

    public UnknownApiKeyException() {
        super("API key not found");
    }
}
