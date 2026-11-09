package wprebuild.app.ontology.frame;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.Optional;

/** A literal filler. {@code datatype} is the datatype IRI. {@code lang} is omitted when absent. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record LiteralValue(String lexical, String datatype, Optional<String> lang) implements ValueNode {

    public LiteralValue {
        Objects.requireNonNull(lexical, "lexical");
        Objects.requireNonNull(datatype, "datatype");
        if (datatype.isBlank()) {
            throw new IllegalArgumentException("datatype is blank");
        }
        lang = language(lang);
    }

    static Optional<String> language(Optional<String> lang) {
        Optional<String> value = lang == null ? Optional.empty() : lang;
        if (value.isPresent() && value.get().isBlank()) {
            throw new IllegalArgumentException("lang is blank");
        }
        return value;
    }
}
