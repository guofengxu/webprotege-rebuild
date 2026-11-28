package wprebuild.kernel.form;

import java.util.Objects;
import java.util.UUID;

/** Identifier of one field inside a {@link FormDescriptor}. */
public record FormFieldId(UUID value) {

    public FormFieldId {
        Objects.requireNonNull(value, "value");
    }
}
