package wprebuild.kernel.id;

import java.util.Objects;
import java.util.UUID;

/** Identifier of one ontology project. The textual form is a UUID. */
public record ProjectId(UUID value) {

    public ProjectId {
        Objects.requireNonNull(value, "project id");
    }

    public static ProjectId of(String raw) {
        Objects.requireNonNull(raw, "project id");
        try {
            return new ProjectId(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("project id is not a UUID: " + raw, ex);
        }
    }

    public static ProjectId random() {
        return new ProjectId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
