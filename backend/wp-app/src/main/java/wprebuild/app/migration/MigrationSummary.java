package wprebuild.app.migration;

import java.util.List;
import java.util.Objects;

/** Counts and warnings written by one successful package import. */
public record MigrationSummary(int projects, int revisions, int usersCreated, List<String> warnings) {

    public MigrationSummary {
        if (projects < 0 || revisions < 0 || usersCreated < 0) {
            throw new IllegalArgumentException("migration counts must be >= 0");
        }
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
