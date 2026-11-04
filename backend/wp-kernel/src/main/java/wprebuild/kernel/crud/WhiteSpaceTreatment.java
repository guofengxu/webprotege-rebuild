package wprebuild.kernel.crud;

import java.util.Objects;

/** How a supplied name's whitespace becomes an IRI suffix (BS-06). Camel case is the default. */
public enum WhiteSpaceTreatment {
    CAMEL_CASE,
    UNDERSCORES;

    public String transform(String input) {
        String trimmed = Objects.requireNonNull(input, "name").trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return switch (this) {
            case CAMEL_CASE -> camelCase(trimmed);
            case UNDERSCORES -> trimmed.replaceAll("\\s+", "_");
        };
    }

    private static String camelCase(String trimmed) {
        String[] words = trimmed.split("\\s+");
        StringBuilder out = new StringBuilder(words[0]);
        for (int index = 1; index < words.length; index++) {
            out.append(capitalize(words[index]));
        }
        return out.toString();
    }

    private static String capitalize(String word) {
        if (word.isEmpty()) {
            return word;
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
