package wprebuild.kernel.shortform;

import java.util.Locale;
import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;

/**
 * One entry in a project's display-language list (BS-09).
 *
 * <p>{@code lang} is stored in lower case. An empty language matches a literal that has no language
 * tag.
 */
public record DisplayNameSetting(IRI annotationProperty, String lang) {

    public DisplayNameSetting {
        Objects.requireNonNull(annotationProperty, "annotationProperty");
        Objects.requireNonNull(lang, "lang");
        lang = lang.toLowerCase(Locale.ROOT);
    }
}
