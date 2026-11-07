package wprebuild.migrator.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import wprebuild.migrator.Main;
import wprebuild.migrator.export.ProjectExporter;

class MongoMetadataTest {

    private static final String PROJECT_ID = "2f0b8a6e-6c1e-4c2d-9a51-0b5e0c3c1d11";
    private static final String OTHER_PROJECT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant EXPORTED_AT = Instant.parse("2026-11-05T08:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(EXPORTED_AT, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void maps_each_legacy_role_onto_the_single_mvp_role() {
        assertMapped("CAN_VIEW", "CAN_VIEW");
        assertMapped("CanView", "CAN_VIEW");
        assertMapped("CAN_COMMENT", "CAN_COMMENT");
        assertMapped("CanComment", "CAN_COMMENT");
        assertMapped("CAN_EDIT", "CAN_EDIT");
        assertMapped("CanEdit", "CAN_EDIT");
        assertMapped("CAN_MANAGE", "CAN_MANAGE");
        assertMapped("CanManage", "CAN_MANAGE");
        assertMapped("PROJECT_VIEWER", "CAN_VIEW");
        assertMapped("ProjectViewer", "CAN_VIEW");
        assertMapped("OBJECT_COMMENTER", "CAN_COMMENT");
        assertMapped("ObjectCommenter", "CAN_COMMENT");
        assertMapped("ISSUE_VIEWER", "CAN_COMMENT");
        assertMapped("IssueViewer", "CAN_COMMENT");
        assertMapped("ISSUE_COMMENTER", "CAN_COMMENT");
        assertMapped("IssueCommenter", "CAN_COMMENT");
        assertMapped("ISSUE_CREATOR", "CAN_COMMENT");
        assertMapped("IssueCreator", "CAN_COMMENT");
        assertMapped("ISSUE_MANAGER", "CAN_COMMENT");
        assertMapped("IssueManager", "CAN_COMMENT");
        assertMapped("ISSUE_CUSTOM", "CAN_COMMENT");
        assertMapped("PROJECT_EDITOR", "CAN_EDIT");
        assertMapped("ProjectEditor", "CAN_EDIT");
        assertMapped("PROJECT_MANAGER", "CAN_MANAGE");
        assertMapped("ProjectManager", "CAN_MANAGE");
        assertMapped("LAYOUT_EDITOR", "CAN_VIEW");
        assertMapped("LayoutEditor", "CAN_VIEW");
        assertMapped("PROJECT_DOWNLOADER", "CAN_VIEW");
        assertMapped("ProjectDownloader", "CAN_VIEW");
        assertThat(RoleMapping.mapRole("PROJECT_CREATOR")).isEmpty();
        assertThat(RoleMapping.mapRole("SystemAdmin")).isEmpty();
        assertThat(RoleMapping.mapRole("ProjectUploader")).isEmpty();
    }

    @Test
    void keeps_the_highest_role_when_one_user_has_several() {
        RoleMapping.Collapsed merged =
                RoleMapping.collapse(List.of("LayoutEditor", "ProjectDownloader", "ProjectEditor", "ProjectViewer"));
        assertThat(merged.role()).contains("CAN_EDIT");
        assertThat(merged.unknownRoles()).isEmpty();

        RoleMapping.Collapsed managed =
                RoleMapping.collapse(List.of("CAN_VIEW", "CAN_MANAGE", "OBJECT_COMMENTER", "MysteryRole"));
        assertThat(managed.role()).contains("CAN_MANAGE");
        assertThat(managed.unknownRoles()).containsExactly("MysteryRole");

        RoleMapping.Collapsed viewOnly = RoleMapping.collapse(List.of("LAYOUT_EDITOR", "PROJECT_DOWNLOADER"));
        assertThat(viewOnly.role()).contains("CAN_VIEW");
    }

    @Test
    void export_copies_mongo_metadata_and_drops_password_fields() throws Exception {
        Path dataDir = emptyProject(PROJECT_ID);
        Path mongo = mongoDir();
        Files.writeString(mongo.resolve("ProjectDetails.json"), projectDetails(), StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("Users.json"), users(), StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("RoleAssignments.json"), roleAssignments(), StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("PrefixDeclarations.json"), prefixes(), StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("EntityCrudKitSettings.json"), crudSettings(), StandardCharsets.UTF_8);

        MongoMetadata metadata = MongoMetadata.load(mongo);
        new ProjectExporter(clock).export(dataDir, outDir(), metadata);

        JsonNode manifest = json.readTree(Files.readString(projectDir().resolve("manifest.json")));
        assertThat(fieldNames(manifest))
                .containsExactly(
                        "formatVersion",
                        "projectId",
                        "exportedAt",
                        "source",
                        "details",
                        "ontologies",
                        "stats",
                        "prefixes",
                        "entityCrudKitSettings",
                        "displayNameSettings",
                        "roleAssignments");
        JsonNode details = manifest.get("details");
        assertThat(fieldNames(details))
                .containsExactly(
                        "displayName",
                        "description",
                        "owner",
                        "inTrash",
                        "createdAt",
                        "createdBy",
                        "modifiedAt",
                        "modifiedBy");
        assertThat(details.get("displayName").asText()).isEqualTo("Warehouse");
        assertThat(details.get("description").asText()).isEmpty();
        assertThat(details.get("owner").asText()).isEqualTo("alice");
        assertThat(details.get("inTrash").asBoolean()).isTrue();
        assertThat(details.get("createdAt").longValue()).isEqualTo(1_690_000_000_000L);
        assertThat(details.get("createdBy").asText()).isEqualTo("alice");
        assertThat(details.get("modifiedAt").longValue()).isEqualTo(1_700_000_000_000L);
        assertThat(details.get("modifiedBy").asText()).isEqualTo("bob");
        assertThat(details.has("defaultLanguage")).isFalse();
        assertThat(manifest.get("prefixes").get("wh").asText()).isEqualTo("http://example.org/wh#");
        assertThat(manifest.get("prefixes").get("ex").asText()).isEqualTo("http://example.org/ex#");
        assertThat(manifest.get("prefixes").has("wh:")).isFalse();
        assertThat(manifest.get("displayNameSettings").get("raw"))
                .isEqualTo(json.readTree(projectDetails()).get(0).get("defaultDisplayNameSettings"));
        assertThat(manifest.get("entityCrudKitSettings").get("raw"))
                .isEqualTo(json.readTree(crudSettings()).get(0));
        assertThat(manifest.get("roleAssignments")).hasSize(2);
        assertThat(manifest.get("roleAssignments").get(0).get("username").asText())
                .isEqualTo("bob");
        assertThat(manifest.get("roleAssignments").get(0).get("roles"))
                .extracting(JsonNode::asText)
                .containsExactly("CAN_EDIT");
        assertThat(manifest.get("roleAssignments").get(1).get("username").asText())
                .isEqualTo("dave");
        assertThat(manifest.get("roleAssignments").get(1).get("roles"))
                .extracting(JsonNode::asText)
                .containsExactly("CAN_VIEW");

        JsonNode users = json.readTree(Files.readString(outDir().resolve("users.json")));
        assertThat(users).hasSize(2);
        assertThat(fieldNames(users.get(0))).containsExactly("username", "realName", "email");
        assertThat(users.get(0).get("username").asText()).isEqualTo("alice");
        assertThat(users.get(0).get("realName").asText()).isEqualTo("Alice");
        assertThat(users.get(0).get("email").asText()).isEqualTo("alice@example.com");
        assertThat(users.get(1).get("username").asText()).isEqualTo("bob");
        assertThat(users.get(1).get("email").asText()).isEmpty();
        assertNoPasswordFields(users);

        JsonNode report = json.readTree(Files.readString(outDir().resolve("migration-report.json")));
        assertThat(report.get("warnings").toString()).contains("MysteryRole");
        assertThat(report.get("warnings").toString()).contains("SystemAdmin");
        assertThat(report.get("warnings").toString()).contains("no userName");
        assertThat(report.get("warnings").toString()).doesNotContain("ProjectManager");
    }

    @Test
    void rejects_trailing_json_and_inexact_timestamps() throws Exception {
        Path mongo = mongoDir();
        Files.writeString(mongo.resolve("Users.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("RoleAssignments.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("PrefixDeclarations.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("EntityCrudKitSettings.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("ProjectDetails.json"), "[] trailing", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> MongoMetadata.load(mongo)).isInstanceOf(IllegalArgumentException.class);

        Files.writeString(
                mongo.resolve("ProjectDetails.json"),
                "[{\"_id\":\"p\",\"displayName\":\"n\",\"createdAt\":1690000000000.9}]",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> MongoMetadata.load(mongo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdAt");
    }

    @Test
    void missing_mongoexport_file_warns_and_still_exports() throws Exception {
        Path dataDir = emptyProject(PROJECT_ID);
        Path mongo = mongoDir();
        Files.writeString(mongo.resolve("ProjectDetails.json"), projectDetails(), StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("Users.json"), users(), StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("RoleAssignments.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(mongo.resolve("EntityCrudKitSettings.json"), "[]", StandardCharsets.UTF_8);

        Capture capture = run(
                "export",
                "--data-dir",
                dataDir.toString(),
                "--out",
                outDir().toString(),
                "--mongo-export-dir",
                mongo.toString());

        assertThat(capture.exitCode()).isZero();
        assertThat(capture.err()).contains("PrefixDeclarations.json");
        JsonNode manifest = json.readTree(Files.readString(projectDir().resolve("manifest.json")));
        assertThat(manifest.get("details").get("displayName").asText()).isEqualTo("Warehouse");
        assertThat(manifest.get("prefixes")).isEmpty();
        assertThat(manifest.get("entityCrudKitSettings").get("raw")).isEmpty();
        JsonNode report = json.readTree(Files.readString(outDir().resolve("migration-report.json")));
        assertThat(report.get("warnings").toString()).contains("PrefixDeclarations.json");
        assertNoPasswordFields(json.readTree(Files.readString(outDir().resolve("users.json"))));
    }

    @Test
    void rejects_a_mongo_export_directory_that_is_not_a_directory() {
        Path file = tempDir.resolve("not-a-dir");
        assertThatThrownBy(() -> MongoMetadata.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-dir");
    }

    private static void assertMapped(String legacyRole, String mvpRole) {
        assertThat(RoleMapping.mapRole(legacyRole)).contains(mvpRole);
    }

    private void assertNoPasswordFields(JsonNode node) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                assertThat(lower)
                        .doesNotContain("password")
                        .doesNotContain("salt")
                        .doesNotContain("digest");
                assertNoPasswordFields(node.get(name));
            });
        } else if (node.isArray()) {
            node.forEach(this::assertNoPasswordFields);
        }
    }

    private Path emptyProject(String projectId) throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(
                dataDir.resolve("data-store").resolve("project-data").resolve(projectId));
        return dataDir;
    }

    private Path mongoDir() throws Exception {
        Path mongo = tempDir.resolve("mongo");
        Files.createDirectories(mongo);
        return mongo;
    }

    private Path outDir() {
        return tempDir.resolve("out");
    }

    private Path projectDir() {
        return outDir().resolve("projects").resolve(PROJECT_ID);
    }

    private static String projectDetails() {
        return """
                [{
                  "_id": "%s",
                  "displayName": "Warehouse",
                  "owner": "alice",
                  "inTrash": true,
                  "defaultLanguage": {"type": "LocalName"},
                  "defaultDisplayNameSettings": {
                    "primaryDisplayNameLanguages": [
                      {"type": "AnnotationAssertion", "propertyIri": "http://www.w3.org/2000/01/rdf-schema#label", "lang": "en"}
                    ],
                    "secondaryDisplayNameLanguages": []
                  },
                  "createdAt": "2023-07-22T04:26:40Z",
                  "createdBy": "alice",
                  "modifiedAt": {"$date": {"$numberLong": "1700000000000"}},
                  "modifiedBy": "bob"
                }]
                """.formatted(PROJECT_ID);
    }

    private static String users() {
        return """
                [{
                  "_id": "alice",
                  "realName": "Alice",
                  "emailAddress": "alice@example.com",
                  "avatar": "http://example.test/alice.png",
                  "salt": "c2FsdA==",
                  "saltedPasswordDigest": "ZGlnZXN0"
                }, {
                  "_id": "bob",
                  "realName": "Bob",
                  "emailAddress": "",
                  "salt": "b3RoZXI=",
                  "saltedPasswordDigest": "b3RoZXItZGlnZXN0"
                }]
                """;
    }

    private static String roleAssignments() {
        return """
                [{
                  "userName": "bob",
                  "projectId": "%s",
                  "assignedRoles": ["ProjectEditor", "LayoutEditor", "MysteryRole"],
                  "roleClosure": ["ProjectManager", "ProjectEditor", "ProjectViewer"],
                  "actionClosure": ["EditOntology"]
                }, {
                  "userName": "dave",
                  "projectId": "%s",
                  "assignedRoles": ["PROJECT_VIEWER", "PROJECT_DOWNLOADER"],
                  "roleClosure": [],
                  "actionClosure": []
                }, {
                  "userName": "carol",
                  "projectId": null,
                  "assignedRoles": ["SystemAdmin"],
                  "roleClosure": ["SystemAdmin"],
                  "actionClosure": []
                }, {
                  "userName": null,
                  "projectId": "%s",
                  "assignedRoles": ["LayoutEditor"],
                  "roleClosure": ["ProjectViewer"],
                  "actionClosure": []
                }, {
                  "userName": "eve",
                  "projectId": "%s",
                  "assignedRoles": ["CanManage"],
                  "roleClosure": [],
                  "actionClosure": []
                }]
                """.formatted(PROJECT_ID, PROJECT_ID, PROJECT_ID, OTHER_PROJECT);
    }

    private static String prefixes() {
        return """
                [{
                  "_id": "%s",
                  "prefixes": {
                    "wh:": "http://example.org/wh#",
                    "ex": "http://example.org/ex#"
                  }
                }]
                """.formatted(PROJECT_ID);
    }

    private static String crudSettings() {
        return """
                [{
                  "_id": "%s",
                  "settings": {
                    "prefixSettings": {"iriPrefix": "http://example.org/wh#", "conditionalIriPrefixes": []},
                    "suffixSettings": {"kitId": "UUID"}
                  }
                }]
                """.formatted(PROJECT_ID);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintWriter outWriter = new PrintWriter(out, true, StandardCharsets.UTF_8);
        PrintWriter errWriter = new PrintWriter(err, true, StandardCharsets.UTF_8);
        CommandLine commandLine = new CommandLine(new Main());
        commandLine.setOut(outWriter);
        commandLine.setErr(errWriter);
        int exitCode = commandLine.execute(args);
        outWriter.flush();
        errWriter.flush();
        return new Capture(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int exitCode, String out, String err) {}
}
