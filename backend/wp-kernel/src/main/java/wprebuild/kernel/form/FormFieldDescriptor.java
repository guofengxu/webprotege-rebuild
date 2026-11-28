package wprebuild.kernel.form;

import java.util.Objects;

/** One field in a form descriptor (control type + OWL binding). */
public record FormFieldDescriptor(FormFieldId id, FormControlType controlType, OwlBinding binding) {

    public FormFieldDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(controlType, "control type");
        Objects.requireNonNull(binding, "binding");
    }

    /**
     * TEXT/NUMBER with a PROPERTY binding may be writable (BS-18). The generator further requires an
     * annotation property, or a data property on a named individual.
     */
    public boolean writableCandidate() {
        return (controlType == FormControlType.TEXT || controlType == FormControlType.NUMBER)
                && binding instanceof OwlPropertyBinding;
    }
}
