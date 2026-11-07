package wprebuild.migrator.legacy;

import java.util.Objects;
import java.util.Optional;

/**
 * A legacy change log could not be read completely. The message names the project and the last
 * revision that was accepted, plus the file offset where reading stopped.
 */
public class LegacyReadException extends RuntimeException {

    private final String projectId;
    private final Long lastSuccessfulRevision;
    private final long offset;

    public LegacyReadException(
            String projectId, Long lastSuccessfulRevision, long offset, String detail, Throwable cause) {
        super(message(projectId, lastSuccessfulRevision, offset, detail), cause);
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.lastSuccessfulRevision = lastSuccessfulRevision;
        this.offset = offset;
    }

    public String projectId() {
        return projectId;
    }

    public Optional<Long> lastSuccessfulRevision() {
        return Optional.ofNullable(lastSuccessfulRevision);
    }

    public long offset() {
        return offset;
    }

    static String revisionText(Long revision) {
        return revision == null ? "none" : Long.toString(revision);
    }

    private static String message(String projectId, Long lastSuccessfulRevision, long offset, String detail) {
        String location = "project=%s lastSuccessfulRevision=%s offset=%d"
                .formatted(
                        Objects.requireNonNull(projectId, "projectId"), revisionText(lastSuccessfulRevision), offset);
        if (detail == null || detail.isBlank()) {
            return location;
        }
        return location + ": " + detail;
    }
}
