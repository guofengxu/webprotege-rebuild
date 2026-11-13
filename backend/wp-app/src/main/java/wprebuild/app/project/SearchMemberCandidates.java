package wprebuild.app.project;

import java.util.List;
import java.util.Objects;
import wprebuild.app.access.UserSummary;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Finds users by id prefix or display-name substring for the member-picker UI. Requires {@code
 * EDIT_SHARING_SETTINGS}.
 */
public record SearchMemberCandidates(ProjectId projectId, String query, int limit)
        implements Command<List<UserSummary>> {

    public static final int MIN_QUERY_LENGTH = 2;

    public static final int MAX_QUERY_LENGTH = 64;

    public static final int MAX_LIMIT = 20;

    public SearchMemberCandidates {
        Objects.requireNonNull(projectId, "project id");
        if (query == null) {
            throw new IllegalArgumentException("query must not be blank");
        }
        query = query.strip();
        if (query.length() < MIN_QUERY_LENGTH || query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "query must be from " + MIN_QUERY_LENGTH + " to " + MAX_QUERY_LENGTH + " characters");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
        }
    }
}
