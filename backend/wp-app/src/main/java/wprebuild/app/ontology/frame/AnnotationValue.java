package wprebuild.app.ontology.frame;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.Optional;
import wprebuild.app.ontology.dto.EntityNode;

/** One annotation assertion on the frame subject. {@code lang} repeats a literal language tag. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record AnnotationValue(EntityNode property, ValueNode value, Optional<String> lang) {

    public AnnotationValue {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(value, "value");
        lang = LiteralValue.language(lang);
    }
}
