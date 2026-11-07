package wprebuild.migrator.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code ProjectDetails} documents. Field names match
 * {@code edu.stanford.bmir.protege.web.shared.project.ProjectDetails}.
 */
public final class ProjectDetailsReader {

    public static final String FILE_NAME = "ProjectDetails.json";

    static final String PROJECT_ID = "_id";
    static final String DISPLAY_NAME = "displayName";
    static final String DESCRIPTION = "description";
    static final String OWNER = "owner";
    static final String IN_TRASH = "inTrash";
    static final String CREATED_AT = "createdAt";
    static final String CREATED_BY = "createdBy";
    static final String MODIFIED_AT = "modifiedAt";
    static final String MODIFIED_BY = "modifiedBy";
    static final String DEFAULT_DISPLAY_NAME_SETTINGS = "defaultDisplayNameSettings";

    private ProjectDetailsReader() {}

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
                    MongoJson.requiredText(node, PROJECT_ID),
                    MongoJson.text(node, DISPLAY_NAME),
                    MongoJson.text(node, DESCRIPTION),
                    MongoJson.text(node, OWNER),
                    MongoJson.bool(node, IN_TRASH),
                    MongoJson.epochMillis(node, CREATED_AT),
                    MongoJson.text(node, CREATED_BY),
                    MongoJson.epochMillis(node, MODIFIED_AT),
                    MongoJson.text(node, MODIFIED_BY),
                    MongoJson.objectOrEmpty(node, DEFAULT_DISPLAY_NAME_SETTINGS));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(FILE_NAME + "[" + index + "]: " + exception.getMessage(), exception);
        }
    }

    public record Document(
            String projectId,
            String displayName,
            String description,
            String owner,
            boolean inTrash,
            long createdAt,
            String createdBy,
            long modifiedAt,
            String modifiedBy,
            JsonNode displayNameSettings) {

        public Map<String, Object> manifestDetails() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put(DISPLAY_NAME, displayName);
            details.put(DESCRIPTION, description);
            details.put(OWNER, owner);
            details.put(IN_TRASH, inTrash);
            details.put(CREATED_AT, createdAt);
            details.put(CREATED_BY, createdBy);
            details.put(MODIFIED_AT, modifiedAt);
            details.put(MODIFIED_BY, modifiedBy);
            return Collections.unmodifiableMap(details);
        }
    }
}
