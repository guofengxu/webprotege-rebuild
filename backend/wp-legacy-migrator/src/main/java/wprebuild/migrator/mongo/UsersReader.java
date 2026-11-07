package wprebuild.migrator.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code Users} documents. Field names match {@code UserRecordConverter}. Salt and
 * {@code saltedPasswordDigest} are never copied.
 */
public final class UsersReader {

    public static final String FILE_NAME = "Users.json";

    static final String USER_ID = "_id";
    static final String REAL_NAME = "realName";
    static final String EMAIL_ADDRESS = "emailAddress";

    private UsersReader() {}

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
                    MongoJson.requiredText(node, USER_ID),
                    MongoJson.text(node, REAL_NAME),
                    MongoJson.text(node, EMAIL_ADDRESS));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(FILE_NAME + "[" + index + "]: " + exception.getMessage(), exception);
        }
    }

    public record Document(String username, String realName, String email) {

        public Map<String, Object> manifestUser() {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("username", username);
            user.put("realName", realName);
            user.put("email", email);
            return Collections.unmodifiableMap(user);
        }
    }
}
