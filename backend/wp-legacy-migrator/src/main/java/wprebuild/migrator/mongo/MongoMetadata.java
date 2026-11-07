package wprebuild.migrator.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Loads the five mongoexport files and serves manifest fragments for one project. */
public final class MongoMetadata {

    private final Map<String, ProjectDetailsReader.Document> details;
    private final Map<String, Map<String, String>> prefixes;
    private final Map<String, JsonNode> crudSettings;
    private final Map<String, List<Map<String, Object>>> roleAssignments;
    private final List<Map<String, Object>> users;
    private final List<String> warnings;
    private final boolean loaded;

    private MongoMetadata(
            Map<String, ProjectDetailsReader.Document> details,
            Map<String, Map<String, String>> prefixes,
            Map<String, JsonNode> crudSettings,
            Map<String, List<Map<String, Object>>> roleAssignments,
            List<Map<String, Object>> users,
            List<String> warnings,
            boolean loaded) {
        this.details = details;
        this.prefixes = prefixes;
        this.crudSettings = crudSettings;
        this.roleAssignments = roleAssignments;
        this.users = users;
        this.warnings = warnings;
        this.loaded = loaded;
    }

    public static MongoMetadata empty() {
        return new MongoMetadata(Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), false);
    }

    public static MongoMetadata load(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("mongo export directory is not a readable directory: " + directory);
        }
        List<String> warnings = new ArrayList<>();
        List<ProjectDetailsReader.Document> projectDetails =
                read(directory, ProjectDetailsReader.FILE_NAME, warnings, ProjectDetailsReader::read);
        List<UsersReader.Document> userDocuments = read(directory, UsersReader.FILE_NAME, warnings, UsersReader::read);
        List<RoleAssignmentsReader.Document> assignments =
                read(directory, RoleAssignmentsReader.FILE_NAME, warnings, RoleAssignmentsReader::read);
        List<PrefixDeclarationsReader.Document> prefixDocuments =
                read(directory, PrefixDeclarationsReader.FILE_NAME, warnings, PrefixDeclarationsReader::read);
        List<EntityCrudKitSettingsReader.Document> crudDocuments =
                read(directory, EntityCrudKitSettingsReader.FILE_NAME, warnings, EntityCrudKitSettingsReader::read);
        return assemble(projectDetails, userDocuments, assignments, prefixDocuments, crudDocuments, warnings);
    }

    public boolean loaded() {
        return loaded;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean hasDetails(String projectId) {
        return details.containsKey(projectId);
    }

    public Map<String, Object> details(String projectId) {
        ProjectDetailsReader.Document document = details.get(projectId);
        if (document == null) {
            return emptyDetails();
        }
        return document.manifestDetails();
    }

    public Map<String, String> prefixes(String projectId) {
        return prefixes.getOrDefault(projectId, Map.of());
    }

    public Map<String, Object> entityCrudKitSettings(String projectId) {
        return raw(crudSettings.getOrDefault(projectId, MongoJson.emptyObject()));
    }

    public Map<String, Object> displayNameSettings(String projectId) {
        ProjectDetailsReader.Document document = details.get(projectId);
        JsonNode settings = document == null ? MongoJson.emptyObject() : document.displayNameSettings();
        return raw(settings);
    }

    public List<Map<String, Object>> roleAssignments(String projectId) {
        return roleAssignments.getOrDefault(projectId, List.of());
    }

    public List<Map<String, Object>> users() {
        return users;
    }

    private static Map<String, Object> emptyDetails() {
        return new ProjectDetailsReader.Document("", "", "", "", false, 0L, "", 0L, "", MongoJson.emptyObject())
                .manifestDetails();
    }

    private static Map<String, Object> raw(JsonNode node) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("raw", node.deepCopy());
        return Collections.unmodifiableMap(wrapper);
    }

    private static <T> List<T> read(Path directory, String fileName, List<String> warnings, Reader<T> reader) {
        Path file = directory.resolve(fileName);
        if (!Files.exists(file)) {
            warnings.add("missing mongoexport file: " + fileName);
            return List.of();
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("mongoexport file is not a readable file: " + file);
        }
        return reader.read(file);
    }

    private static MongoMetadata assemble(
            List<ProjectDetailsReader.Document> projectDetails,
            List<UsersReader.Document> userDocuments,
            List<RoleAssignmentsReader.Document> assignments,
            List<PrefixDeclarationsReader.Document> prefixDocuments,
            List<EntityCrudKitSettingsReader.Document> crudDocuments,
            List<String> warnings) {
        Map<String, ProjectDetailsReader.Document> details =
                firstByProject(projectDetails, ProjectDetailsReader.Document::projectId, "ProjectDetails", warnings);
        Map<String, PrefixDeclarationsReader.Document> prefixDocs = firstByProject(
                prefixDocuments, PrefixDeclarationsReader.Document::projectId, "PrefixDeclarations", warnings);
        Map<String, EntityCrudKitSettingsReader.Document> crudDocs = firstByProject(
                crudDocuments, EntityCrudKitSettingsReader.Document::projectId, "EntityCrudKitSettings", warnings);
        Map<String, Map<String, String>> prefixes = new LinkedHashMap<>();
        prefixDocs.forEach((projectId, document) -> prefixes.put(projectId, document.prefixes()));
        Map<String, JsonNode> crud = new LinkedHashMap<>();
        crudDocs.forEach((projectId, document) -> crud.put(projectId, document.raw()));
        return new MongoMetadata(
                Map.copyOf(details),
                freeze(prefixes),
                Map.copyOf(crud),
                roles(assignments, warnings),
                users(userDocuments, warnings),
                List.copyOf(warnings),
                true);
    }

    private static List<Map<String, Object>> users(List<UsersReader.Document> documents, List<String> warnings) {
        Map<String, Map<String, Object>> users = new LinkedHashMap<>();
        for (UsersReader.Document document : documents) {
            if (users.containsKey(document.username())) {
                warnings.add("duplicate user '" + document.username() + "'");
                continue;
            }
            users.put(document.username(), document.manifestUser());
        }
        return List.copyOf(users.values());
    }

    private static Map<String, List<Map<String, Object>>> roles(
            List<RoleAssignmentsReader.Document> documents, List<String> warnings) {
        Map<String, LinkedHashMap<String, String>> highest = new LinkedHashMap<>();
        for (RoleAssignmentsReader.Document document : documents) {
            remember(highest, document, warnings);
        }
        Map<String, List<Map<String, Object>>> byProject = new LinkedHashMap<>();
        highest.forEach((projectId, users) -> byProject.put(projectId, assignments(users)));
        return Map.copyOf(byProject);
    }

    private static void remember(
            Map<String, LinkedHashMap<String, String>> highest,
            RoleAssignmentsReader.Document document,
            List<String> warnings) {
        RoleMapping.Collapsed collapsed = RoleMapping.collapse(document.assignedRoles());
        if (document.userName().isEmpty()) {
            warnings.add(noUser(document.projectId()));
            unknownRoles(warnings, "(no user)", document.projectId(), collapsed.unknownRoles());
            return;
        }
        String userName = document.userName().get();
        if (document.projectId().isEmpty()) {
            warnings.add("role assignment for user " + userName + " has no projectId");
            unknownRoles(warnings, userName, Optional.empty(), collapsed.unknownRoles());
            return;
        }
        String projectId = document.projectId().get();
        unknownRoles(warnings, userName, document.projectId(), collapsed.unknownRoles());
        if (collapsed.role().isEmpty()) {
            return;
        }
        highest.computeIfAbsent(projectId, ignored -> new LinkedHashMap<>())
                .merge(userName, collapsed.role().get(), RoleMapping::higher);
    }

    private static String noUser(Optional<String> projectId) {
        if (projectId.isEmpty()) {
            return "role assignment has no userName";
        }
        return "role assignment on project " + projectId.get() + " has no userName";
    }

    private static void unknownRoles(
            List<String> warnings, String userName, Optional<String> projectId, List<String> unknown) {
        for (String role : unknown) {
            if (projectId.isEmpty()) {
                warnings.add("unknown role '" + role + "' for user " + userName + " without a project");
            } else {
                warnings.add("unknown role '" + role + "' for user " + userName + " on project " + projectId.get());
            }
        }
    }

    private static List<Map<String, Object>> assignments(Map<String, String> users) {
        List<Map<String, Object>> assignments = new ArrayList<>();
        users.forEach((userName, role) -> {
            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put("username", userName);
            assignment.put("roles", List.of(role));
            assignments.add(Collections.unmodifiableMap(assignment));
        });
        return List.copyOf(assignments);
    }

    private static <T> Map<String, T> firstByProject(
            List<T> documents, Function<T, String> projectId, String label, List<String> warnings) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T document : documents) {
            String id = projectId.apply(document);
            if (indexed.containsKey(id)) {
                warnings.add("duplicate " + label + " for project " + id);
                continue;
            }
            indexed.put(id, document);
        }
        return indexed;
    }

    private static Map<String, Map<String, String>> freeze(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        source.forEach((projectId, prefixes) ->
                copy.put(projectId, Collections.unmodifiableMap(new LinkedHashMap<>(prefixes))));
        return Collections.unmodifiableMap(copy);
    }

    @FunctionalInterface
    private interface Reader<T> {
        List<T> read(Path file);
    }
}
