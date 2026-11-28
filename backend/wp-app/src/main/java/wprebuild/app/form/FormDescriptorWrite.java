package wprebuild.app.form;

import java.util.Objects;
import java.util.UUID;

/** Input for {@link FormDescriptorRepository#replaceAll} and similar writes. */
public record FormDescriptorWrite(UUID formId, String descriptorJson) {

    public FormDescriptorWrite {
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(descriptorJson, "descriptor json");
    }
}
