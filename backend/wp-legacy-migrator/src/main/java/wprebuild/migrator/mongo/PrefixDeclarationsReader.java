package wprebuild.migrator.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code PrefixDeclarations} documents. Field names match
 * {@code edu.stanford.bmir.protege.web.shared.project.PrefixDeclarations}.
 */
public final class PrefixDeclarationsReader {

    public static final String FILE_NAME = "PrefixDeclarations.json";

    static final String PROJECT_ID = "_id";

    private PrefixDeclarationsReader() {}

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
            return new Document(MongoJson.requiredText(node, PROJECT_ID), MongoJson.prefixes(node));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(FILE_NAME + "[" + index + "]: " + exception.getMessage(), exception);
        }
    }

    public record Document(String projectId, Map<String, String> prefixes) {}
}
