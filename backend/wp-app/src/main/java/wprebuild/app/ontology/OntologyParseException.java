package wprebuild.app.ontology;

import java.util.Objects;

/** A caller-supplied ontology document could not be read. {@code line} is 1-based when the parser reported one. */
public final class OntologyParseException extends RuntimeException {

    private final Integer line;

    public OntologyParseException(String message, Integer line, Throwable cause) {
        super(requireMessage(message), cause);
        if (line != null && line < 1) {
            throw new IllegalArgumentException("line must be positive");
        }
        this.line = line;
    }

    public Integer line() {
        return line;
    }

    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }
}
