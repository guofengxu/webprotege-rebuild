package wprebuild.kernel.generator;

import java.util.Objects;

/** Rejects a merge before any change is produced (BS-13). */
public final class MergeEntitiesException extends RuntimeException {

    public enum Reason {
        EMPTY_SOURCES,
        TARGET_IN_SOURCES,
        BUILT_IN_ENTITY,
        UNKNOWN_ENTITY,
        TYPE_MISMATCH
    }

    private final Reason reason;

    public MergeEntitiesException(Reason reason, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
