package wprebuild.kernel.form;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.OWLLiteral;

/** Runtime form payload: descriptor plus per-field values (not persisted; BS-18). */
public record FormData(FormId formId, FormDescriptor descriptor, List<FormFieldValue> values) {

    public FormData {
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(descriptor, "descriptor");
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (!formId.equals(descriptor.formId())) {
            throw new IllegalArgumentException("formId does not match descriptor.formId");
        }
    }

    public static FormData of(FormId formId, FormDescriptor descriptor, List<FormFieldValue> values) {
        return new FormData(formId, descriptor, values);
    }

    public Optional<OWLLiteral> valueOf(FormFieldId fieldId) {
        Objects.requireNonNull(fieldId, "field id");
        for (FormFieldValue value : values) {
            if (value.fieldId().equals(fieldId)) {
                return value.value();
            }
        }
        return Optional.empty();
    }

    Map<FormFieldId, Optional<OWLLiteral>> valueMap() {
        Map<FormFieldId, Optional<OWLLiteral>> map = new LinkedHashMap<>();
        for (FormFieldValue value : values) {
            map.put(value.fieldId(), value.value());
        }
        return Map.copyOf(map);
    }
}
