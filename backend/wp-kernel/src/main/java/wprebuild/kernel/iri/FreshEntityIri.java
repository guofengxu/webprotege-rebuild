package wprebuild.kernel.iri;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;

/**
 * Placeholder IRI a client uses for an entity the server has not named yet (BS-05).
 *
 * <p>The text form starts with {@code entity:?langTag=} and ends with {@code #} plus the supplied
 * name. {@code &} and {@code #} inside a parent IRI are written as {@code %26} and {@code %23}.
 */
public record FreshEntityIri(String suppliedName, String langTag, String discriminator, Set<IRI> parentIris) {

    private static final String SCHEME = "entity";

    private static final String PREFIX = "entity:?";

    private static final String AMPERSAND_ESCAPE = "%26";

    private static final String HASH_ESCAPE = "%23";

    private static final String LANG_TAG_PARAM = "langTag";

    private static final String DISCRIMINATOR_PARAM = "discriminator";

    private static final String PARENT_IRI_PARAM = "parentIri";

    public FreshEntityIri {
        Objects.requireNonNull(suppliedName, "supplied name");
        rejectDelimiter(Objects.requireNonNull(langTag, "lang tag"), "lang tag");
        rejectDelimiter(Objects.requireNonNull(discriminator, "discriminator"), "discriminator");
        parentIris = Set.copyOf(Objects.requireNonNull(parentIris, "parent iris"));
    }

    /** {@code &} and {@code #} are query delimiters, so they cannot appear in these fields. */
    private static void rejectDelimiter(String value, String name) {
        if (value.indexOf('&') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException(name + " must not contain '&' or '#'");
        }
    }

    /** IRI text for this placeholder. Parent IRIs are sorted so the text does not depend on set order. */
    public IRI toIri() {
        StringBuilder text = new StringBuilder(PREFIX)
                .append(LANG_TAG_PARAM)
                .append('=')
                .append(langTag)
                .append('&')
                .append(DISCRIMINATOR_PARAM)
                .append('=')
                .append(discriminator);
        parentIris.stream()
                .sorted(Comparator.comparing(IRI::toString))
                .forEach(parent ->
                        text.append('&').append(PARENT_IRI_PARAM).append('=').append(escape(parent.toString())));
        text.append('#').append(suppliedName);
        return IRI.create(text.toString());
    }

    public static boolean isFresh(IRI iri) {
        return SCHEME.equals(Objects.requireNonNull(iri, "iri").getScheme());
    }

    /** Empty when {@code iri} is not a well-formed fresh entity IRI. */
    public static Optional<FreshEntityIri> parse(IRI iri) {
        Objects.requireNonNull(iri, "iri");
        if (!isFresh(iri)) {
            return Optional.empty();
        }
        String text = iri.toString();
        int fragment = text.indexOf('#');
        if (!text.startsWith(PREFIX) || fragment < 0) {
            return Optional.empty();
        }
        return parseQuery(text.substring(PREFIX.length(), fragment), text.substring(fragment + 1));
    }

    private static Optional<FreshEntityIri> parseQuery(String query, String suppliedName) {
        String langTag = null;
        String discriminator = null;
        Set<IRI> parents = new LinkedHashSet<>();
        if (!query.isEmpty()) {
            for (String token : query.split("&", -1)) {
                int separator = token.indexOf('=');
                if (separator < 0) {
                    return Optional.empty();
                }
                String key = token.substring(0, separator);
                String value = token.substring(separator + 1);
                switch (key) {
                    case LANG_TAG_PARAM -> {
                        if (langTag == null) {
                            langTag = value;
                        }
                    }
                    case DISCRIMINATOR_PARAM -> {
                        if (discriminator == null) {
                            discriminator = value;
                        }
                    }
                    case PARENT_IRI_PARAM -> parents.add(IRI.create(unescape(value)));
                    default -> {
                        // Ignore unknown parameters so a later field can be added without breaking readers.
                    }
                }
            }
        }
        if (langTag == null || discriminator == null) {
            return Optional.empty();
        }
        return Optional.of(new FreshEntityIri(suppliedName, langTag, discriminator, parents));
    }

    private static String escape(String value) {
        return value.replace("&", AMPERSAND_ESCAPE).replace("#", HASH_ESCAPE);
    }

    private static String unescape(String value) {
        return value.replace(AMPERSAND_ESCAPE, "&").replace(HASH_ESCAPE, "#");
    }
}
