package wprebuild.kernel.id;

import java.util.Objects;

/** Identifier of a person or of an automatic actor. Blank text is rejected; surrounding spaces are kept. */
public record UserId(String value) {

    /** Actor used by migration and other automatic work. */
    public static final UserId SYSTEM = new UserId("system");

    public UserId {
        if (Objects.requireNonNull(value, "user id").isBlank()) {
            throw new IllegalArgumentException("user id must not be blank");
        }
    }
}
