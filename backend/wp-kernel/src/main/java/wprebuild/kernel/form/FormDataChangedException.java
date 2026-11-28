package wprebuild.kernel.form;

/**
 * Pristine form-derived assertions no longer match the project runtime (BS-18).
 *
 * <p>Mapped by the app layer to HTTP 409 {@code FORM_DATA_CHANGED}.
 */
public final class FormDataChangedException extends RuntimeException {

    public static final String CODE = "FORM_DATA_CHANGED";

    public FormDataChangedException() {
        super("form data changed since pristine snapshot");
    }

    public String code() {
        return CODE;
    }
}
