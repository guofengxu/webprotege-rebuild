package wprebuild.app.revision;

import java.util.List;
import java.util.Objects;

/** One page of revision metadata, newest revision first. {@code page} is zero-based. */
public record RevisionPage(List<RevisionSummary> revisions, int page, int size, long total) {

    public RevisionPage {
        revisions = List.copyOf(Objects.requireNonNull(revisions, "revisions"));
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (total < revisions.size()) {
            throw new IllegalArgumentException("total is smaller than the page");
        }
    }
}
