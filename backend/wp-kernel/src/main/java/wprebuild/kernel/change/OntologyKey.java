package wprebuild.kernel.change;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier of one ontology inside a project. Textual form is {@code o} plus a positive integer. */
public record OntologyKey(String value) {

    private static final Pattern PATTERN = Pattern.compile("o[1-9][0-9]*");

    public OntologyKey {
        Objects.requireNonNull(value, "ontology key");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("ontology key must match o[1-9][0-9]*: " + value);
        }
    }

    public static OntologyKey of(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("ontology key index must be positive: " + n);
        }
        return new OntologyKey("o" + n);
    }
}
