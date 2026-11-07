package wprebuild.migrator.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code RoleAssignments} documents. Field names match
 * {@code edu.stanford.bmir.protege.web.server.access.RoleAssignment}. {@code roleClosure} is ignored:
 * it includes parent roles and would raise the mapped role above the roles actually assigned.
 */
public final class RoleAssignmentsReader {

    public static final String FILE_NAME = "RoleAssignments.json";

    static final String USER_NAME = "userName";
    static final String PROJECT_ID = "projectId";
    static final String ASSIGNED_ROLES = "assignedRoles";

    private RoleAssignmentsReader() {}

    public static List<Document> read(Path file) {
        List<JsonNode> nodes = MongoJson.array(file);
        List<Document> documents = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            documents.add(readOne(nodes.get(index), index));
        }
        return List.copyOf(documents);
    }

    private static Document readOne(JsonNode node, int index) {
        try {
            return new Document(
                    MongoJson.optionalText(node, USER_NAME),
                    MongoJson.optionalText(node, PROJECT_ID),
                    MongoJson.strings(node, ASSIGNED_ROLES));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(FILE_NAME + "[" + index + "]: " + exception.getMessage(), exception);
        }
    }

    /**
     * {@code userName} is empty for the legacy "any signed-in user" subject. That assignment cannot
     * be written into the interchange file, which requires a username.
     */
    public record Document(Optional<String> userName, Optional<String> projectId, List<String> assignedRoles) {}
}
