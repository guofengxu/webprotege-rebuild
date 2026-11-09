package wprebuild.app.migration;

import java.util.Objects;

/** One failed interchange rule. {@code location} is a package-relative path, plus a revision when known. */
public record MigrationFailure(String rule, String location, String detail) {

    public MigrationFailure {
        rule = require(rule, "rule");
        location = require(location, "location");
        detail = require(detail, "detail");
    }

    private static String require(String value, String label) {
        if (Objects.requireNonNull(value, label).isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
