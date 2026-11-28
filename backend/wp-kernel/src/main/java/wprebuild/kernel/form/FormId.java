package wprebuild.kernel.form;

import java.util.Objects;
import java.util.UUID;

/** Stable identifier of one project form descriptor. */
public record FormId(UUID value) {

    public FormId {
        Objects.requireNonNull(value, "value");
    }
}
