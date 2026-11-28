package wprebuild.kernel.form;

import java.util.Objects;

/** Binding kinds outside the first writable slice (CLASS, INSTANCE, SUBCLASS, …). */
public record UnsupportedOwlBinding(String type) implements OwlBinding {

    public UnsupportedOwlBinding {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type is blank");
        }
    }
}
