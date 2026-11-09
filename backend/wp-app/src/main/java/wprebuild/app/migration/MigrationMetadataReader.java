package wprebuild.app.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.ProjectRole;

/** Reads {@code users.json} and the manifest fields this import stores beside the revision log. */
final class MigrationMetadataReader {

    private static final JsonMapper JSON = new JsonMapper();

    private static final List<ProjectRole> RANK =
            List.of(ProjectRole.CAN_VIEW, ProjectRole.CAN_COMMENT, ProjectRole.CAN_EDIT, ProjectRole.CAN_MANAGE);

    private static final Set<String> SECRET_FIELDS =
            Set.of("password", "passwordhash", "password_hash", "salt", "saltedpassworddigest", "digest", "secret");

    private MigrationMetadataReader() {}

    static List<MigrationUser> users(JsonNode node, List<MigrationFailure> failures) {
        if (node == null || !node.isArray()) {
            failures.add(new MigrationFailure("users", "users.json", "users.json must be an array"));
            return List.of();
        }
        List<MigrationUser> users = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode entry : node) {
            MigrationUser user = user(entry, index, seen, failures);
            if (user != null) {
                users.add(user);
            }
            index++;
        }
        return List.copyOf(users);
    }

    static ProjectMetadata project(JsonNode manifest, String location, List<MigrationFailure> failures) {
        String where = location + "/manifest.json";
        Map<String, String> prefixes = prefixes(manifest, where, failures);
        List<String> warnings = new ArrayList<>();
        List<ImportedRole> roles = roles(manifest, where, warnings, failures);
        JsonNode crud = objectField(
                manifest.path("entityCrudKitSettings").path("raw"), "entityCrudKitSettings.raw", where, failures);
        JsonNode display = objectField(
                manifest.path("displayNameSettings").path("raw"), "displayNameSettings.raw", where, failures);
        return new ProjectMetadata(prefixes, roles, warnings, crud, display);
    }

    private static MigrationUser user(
            JsonNode entry, int index, Map<String, Integer> seen, List<MigrationFailure> failures) {
        String where = "users.json[" + index + "]";
        if (entry == null || !entry.isObject()) {
            failures.add(new MigrationFailure("users", where, "user must be an object"));
            return null;
        }
        String username = text(entry, "username");
        if (username == null || username.isBlank()) {
            failures.add(new MigrationFailure("users", where, "username is required"));
            return null;
        }
        if (containsControl(username)) {
            failures.add(new MigrationFailure("users", where, "username contains a control character"));
            return null;
        }
        if (seen.put(username, index) != null) {
            failures.add(new MigrationFailure("users", where, "duplicate username"));
            return null;
        }
        String realName = optionalText(entry, "realName", where, failures);
        if (realName == null) {
            return null;
        }
        String email = optionalText(entry, "email", where, failures);
        if (email == null) {
            return null;
        }
        if (containsControl(realName)) {
            failures.add(new MigrationFailure("users", where, "realName contains a control character"));
            return null;
        }
        if (containsControl(email)) {
            failures.add(new MigrationFailure("users", where, "email contains a control character"));
            return null;
        }
        return new MigrationUser(username, realName, blankToNull(email), discardedSecret(entry));
    }

    private static Map<String, String> prefixes(JsonNode manifest, String where, List<MigrationFailure> failures) {
        JsonNode node = manifest.get("prefixes");
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            failures.add(new MigrationFailure("manifest", where, "prefixes must be an object"));
            return Map.of();
        }
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (String name : node.propertyNames()) {
            putPrefix(prefixes, name, node.get(name), where, failures);
        }
        return prefixes;
    }

    private static void putPrefix(
            Map<String, String> prefixes, String name, JsonNode value, String where, List<MigrationFailure> failures) {
        if (name == null || name.isBlank() || containsControl(name)) {
            failures.add(new MigrationFailure("manifest", where, "prefix name is blank"));
            return;
        }
        if (value == null || !value.isString() || value.asString().isBlank() || containsControl(value.asString())) {
            failures.add(new MigrationFailure("manifest", where, "prefix " + shown(name) + " must be a string"));
            return;
        }
        prefixes.put(name, value.asString());
    }

    private static List<ImportedRole> roles(
            JsonNode manifest, String where, List<String> warnings, List<MigrationFailure> failures) {
        JsonNode listed = manifest.get("roleAssignments");
        if (listed == null || listed.isNull()) {
            return List.of();
        }
        if (!listed.isArray()) {
            failures.add(new MigrationFailure("manifest", where, "roleAssignments must be an array"));
            return List.of();
        }
        Map<String, ProjectRole> byUser = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode entry : listed) {
            readRole(entry, where + ":roleAssignments[" + index + "]", byUser, warnings, failures);
            index++;
        }
        List<ImportedRole> roles = new ArrayList<>();
        byUser.forEach((username, role) -> roles.add(new ImportedRole(username, role)));
        return roles;
    }

    private static void readRole(
            JsonNode entry,
            String where,
            Map<String, ProjectRole> byUser,
            List<String> warnings,
            List<MigrationFailure> failures) {
        if (entry == null || !entry.isObject()) {
            failures.add(new MigrationFailure("manifest", where, "role assignment must be an object"));
            return;
        }
        String username = text(entry, "username");
        if (username == null || username.isBlank() || containsControl(username)) {
            failures.add(new MigrationFailure("manifest", where, "username is required"));
            return;
        }
        JsonNode listed = entry.get("roles");
        if (listed == null || !listed.isArray()) {
            failures.add(new MigrationFailure("manifest", where, "roles must be an array"));
            return;
        }
        ProjectRole highest = byUser.get(username);
        boolean any = highest != null;
        for (JsonNode roleNode : listed) {
            if (roleNode == null || !roleNode.isString() || roleNode.asString().isBlank()) {
                failures.add(new MigrationFailure("manifest", where, "role must be a string"));
                return;
            }
            ProjectRole role = role(roleNode.asString());
            if (role == null) {
                warnings.add("unrecognized role " + shown(roleNode.asString()) + " for " + shown(username));
                continue;
            }
            any = true;
            highest = highest == null ? role : higher(highest, role);
        }
        if (!any) {
            warnings.add("role assignment for " + shown(username) + " has no role");
            return;
        }
        if (highest != null) {
            byUser.put(username, highest);
        }
    }

    private static ProjectRole role(String name) {
        for (ProjectRole candidate : RANK) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static ProjectRole higher(ProjectRole left, ProjectRole right) {
        return RANK.indexOf(left) >= RANK.indexOf(right) ? left : right;
    }

    private static JsonNode objectField(JsonNode node, String field, String where, List<MigrationFailure> failures) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JSON.createObjectNode();
        }
        if (!node.isObject()) {
            failures.add(new MigrationFailure("manifest", where, field + " must be an object"));
            return JSON.createObjectNode();
        }
        return node;
    }

    private static String optionalText(JsonNode entry, String field, String where, List<MigrationFailure> failures) {
        JsonNode value = entry.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isString()) {
            failures.add(new MigrationFailure("users", where, field + " must be a string"));
            return null;
        }
        return value.asString();
    }

    private static boolean discardedSecret(JsonNode entry) {
        for (String name : entry.propertyNames()) {
            if (!SECRET_FIELDS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            JsonNode value = entry.get(name);
            if (value != null && !value.isNull()) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isString()) {
            return null;
        }
        return value.asString();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static String shown(String value) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length() && out.length() < 80; index++) {
            char current = value.charAt(index);
            out.append(Character.isISOControl(current) ? ' ' : current);
        }
        return out.toString();
    }
}
