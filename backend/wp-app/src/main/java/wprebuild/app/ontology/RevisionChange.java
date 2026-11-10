package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/** One rendered change. {@code op} is {@code ADD} or {@code REMOVE}. {@code text} is Manchester syntax. */
public record RevisionChange(String op, EntityNode subject, String text) {

    public RevisionChange {
        if (!"ADD".equals(op) && !"REMOVE".equals(op)) {
            throw new IllegalArgumentException("op must be ADD or REMOVE");
        }
        Objects.requireNonNull(subject, "subject");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
