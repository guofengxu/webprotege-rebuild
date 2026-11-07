package wprebuild.migrator.legacy;

/**
 * The legacy reader rejects {@code SetOntologyIDData}. {@code revision} is the revision that
 * contains it, and {@code changeIndex} is its 0-based position in that revision.
 */
public final class UnsupportedLegacyChangeException extends LegacyReadException {

    private final long revision;
    private final int changeIndex;

    public UnsupportedLegacyChangeException(
            String projectId, long revision, int changeIndex, Long lastSuccessfulRevision, long offset) {
        super(
                projectId,
                lastSuccessfulRevision,
                offset,
                "revision=%d changeIndex=%d: unsupported SetOntologyID".formatted(revision, changeIndex),
                null);
        if (changeIndex < 0) {
            throw new IllegalArgumentException("changeIndex must be >= 0");
        }
        this.revision = revision;
        this.changeIndex = changeIndex;
    }

    public long revision() {
        return revision;
    }

    public int changeIndex() {
        return changeIndex;
    }
}
