package wprebuild.kernel.form;

import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.OWLLiteral;

/** Value of one form field. Empty means the field clears its bound assertion. */
public record FormFieldValue(FormFieldId fieldId, Optional<OWLLiteral> value) {

    public FormFieldValue {
        Objects.requireNonNull(fieldId, "field id");
        Objects.requireNonNull(value, "value");
    }
}
