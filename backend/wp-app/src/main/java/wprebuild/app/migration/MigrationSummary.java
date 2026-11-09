package wprebuild.app.migration;

/** Counts written by one successful package import. */
public record MigrationSummary(int projects, int revisions, int usersCreated) {

    public MigrationSummary {
        if (projects < 0 || revisions < 0 || usersCreated < 0) {
            throw new IllegalArgumentException("migration counts must be >= 0");
        }
    }
}
