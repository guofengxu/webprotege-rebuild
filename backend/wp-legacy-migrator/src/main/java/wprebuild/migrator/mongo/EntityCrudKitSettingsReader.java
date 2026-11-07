package wprebuild.migrator.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code EntityCrudKitSettings} documents. The stored document is
 * {@code ProjectEntityCrudKitSettings}: {@code _id} plus {@code settings}. The whole document is kept
 * as {@code raw} for P3-10.
 */
public final class EntityCrudKitSettingsReader {

    public static final String FILE_NAME = "EntityCrudKitSettings.json";

    static final String PROJECT_ID = "_id";

    private EntityCrudKitSettingsReader() {}

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
            if (!node.isObject()) {
                throw new IllegalArgumentException("document is not an object");
            }
            return new Document(MongoJson.requiredText(node, PROJECT_ID), node.deepCopy());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(FILE_NAME + "[" + index + "]: " + exception.getMessage(), exception);
        }
    }

    public record Document(String projectId, JsonNode raw) {

        public Document {
            raw = raw.deepCopy();
        }

        @Override
        public JsonNode raw() {
            return raw.deepCopy();
        }
    }
}
