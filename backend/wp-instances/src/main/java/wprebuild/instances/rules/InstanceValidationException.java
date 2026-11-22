package wprebuild.instances.rules;

import java.util.List;
import java.util.Objects;

/** Thrown when a validating writer rejects an instance or link; nothing is persisted. */
public final class InstanceValidationException extends RuntimeException {

    private final List<Violation> violations;

    public InstanceValidationException(List<Violation> violations) {
        super(summarize(violations));
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        if (this.violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
    }

    public List<Violation> violations() {
        return violations;
    }

    private static String summarize(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "instance validation failed";
        }
        return "instance validation failed: " + violations.getFirst().code() + " (" + violations.size() + ")";
    }
}
