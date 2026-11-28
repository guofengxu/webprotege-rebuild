package wprebuild.kernel.form;

import java.util.Objects;

/**
 * Edited value differs from pristine for a control or binding outside the first writable slice
 * (BS-18).
 */
public final class UnsupportedFormControlEditException extends RuntimeException {

    private final FormFieldId fieldId;

    public UnsupportedFormControlEditException(FormFieldId fieldId) {
        super("unsupported form control edit for field "
                + Objects.requireNonNull(fieldId, "field id").value());
        this.fieldId = fieldId;
    }

    public FormFieldId fieldId() {
        return fieldId;
    }
}
