package wprebuild.kernel.form;

import java.util.List;
import java.util.Objects;

/** Form layout: identifier plus ordered field descriptors. */
public record FormDescriptor(FormId formId, List<FormFieldDescriptor> fields) {

    public FormDescriptor {
        Objects.requireNonNull(formId, "form id");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
