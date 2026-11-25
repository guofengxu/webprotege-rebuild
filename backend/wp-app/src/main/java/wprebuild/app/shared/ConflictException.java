package wprebuild.app.shared;

import java.util.Map;
import java.util.Objects;

/** The request conflicts with the current state. {@code code} distinguishes the conflict. */
public final class ConflictException extends CodedException {

    public static final String CODE = "CONFLICT";

    private final Map<String, Object> properties;

    public ConflictException(String message) {
        this(CODE, message);
    }

    public ConflictException(String code, String message) {
        this(code, message, Map.of());
    }

    public ConflictException(String code, String message, Map<String, ?> properties) {
        super(code, message);
        this.properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    /** Extra ProblemDetail fields (for example {@code projectIds} on {@code LAST_MANAGER}). */
    public Map<String, Object> properties() {
        return properties;
    }
}
