package wprebuild.app.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.RoleAssignment;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.project.ProjectRepository;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Imports users, roles, prefixes, and legacy settings. Password fields in the package are ignored.
 */
@DatabaseTest
class MigrationMetadataTest {

    private static final ProjectId PROJECT = ProjectId.of("2f0b8a6e-6c1e-4c2d-9a51-0b5e0c3c1d11");

    private static final String SECRET = "legacy-secret-should-not-land";

    private static final JsonMapper JSON = new JsonMapper();

    private final ProjectMigrator migrator;

    private final ProjectRepository projects;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final JdbcClient jdbc;

    MigrationMetadataTest(
            ProjectMigrator migrator,
            ProjectRepository projects,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            JdbcClient jdbc) {
        this.migrator = migrator;
        this.projects = projects;
        this.users = users;
        this.roles = roles;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void removeSampleProject() {
        deleteProject(PROJECT);
        deleteUserIfUnreferenced("alice");
        deleteUserIfUnreferenced("bob");
        deleteUserIfUnreferenced("carol");
        deleteUserIfUnreferenced("dave");
    }

    @Test
    void sample_package_stores_defaults_and_warns_about_the_empty_kit() {
        boolean aliceMissing = users.findById(new UserId("alice")).isEmpty();

        MigrationSummary summary = migrator.importPackage(samplePackage(), false);

        assertThat(roles.list(PROJECT)).isEmpty();
        assertThat(setting("prefixes")).isEqualTo(JSON.createObjectNode());
        assertThat(setting("display-names")).isEqualTo(JSON.createArrayNode());
        JsonNode entity = setting("new-entity");
        assertThat(entity.get("iriPrefix").asString()).isEqualTo("http://www.example.org/");
        assertThat(entity.get("kit").get("type").asString()).isEqualTo("UUID");
        assertThat(entity.get("labelProperty").asString()).isEqualTo("http://www.w3.org/2000/01/rdf-schema#label");
        assertThat(entity.get("defaultLang").asString()).isEmpty();
        assertThat(entity.get("targetOntology").asString()).isEqualTo("o1");
        ProjectDetails details = projects.find(PROJECT).orElseThrow();
        assertThat(details.displayName()).isEqualTo(PROJECT.toString());
        assertThat(details.owner()).isEqualTo(UserId.SYSTEM);
        assertThat(summary.warnings()).anyMatch(warning -> warning.contains("unrecognized entity crud kit"));
        if (aliceMissing) {
            assertThat(account("alice").passwordResetRequired()).isTrue();
        }
    }

    @Test
    void imports_users_roles_prefixes_and_settings_without_passwords(@TempDir Path temp) throws IOException {
        UserAccount existing = users.create("bob", "Robert", "bob@old.example", "correct-horse", List.of());
        Path packaged = copyPackage(samplePackage(), temp.resolve("package"));
        writeUsers(packaged);
        patchManifest(packaged.resolve("projects").resolve(PROJECT.toString()));

        MigrationSummary summary = migrator.importPackage(packaged, false);

        UserAccount bob = account("bob");
        assertThat(bob.displayName()).isEqualTo("Robert");
        assertThat(bob.email()).isEqualTo("bob@old.example");
        assertThat(bob.passwordHash()).isEqualTo(existing.passwordHash());
        UserAccount alice = account("alice");
        assertThat(alice.displayName()).isEqualTo("Alice");
        assertThat(alice.email()).isEqualTo("alice@example.com");
        assertThat(alice.passwordResetRequired()).isTrue();
        UserAccount carol = account("carol");
        assertThat(carol.displayName()).isEqualTo("Carol");
        assertThat(carol.email()).isEqualTo("carol@example.com");
        assertThat(carol.passwordResetRequired()).isTrue();
        assertThat(account("dave").displayName()).isEqualTo("dave");
        assertThat(account("dave").passwordResetRequired()).isTrue();
        assertThat(passwordColumn("carol")).isNull();
        assertThat(passwordColumn("alice")).isNull();
        assertThat(roles.list(PROJECT))
                .containsExactly(
                        new RoleAssignment(PROJECT, new UserId("bob"), ProjectRole.CAN_EDIT),
                        new RoleAssignment(PROJECT, new UserId("carol"), ProjectRole.CAN_MANAGE),
                        new RoleAssignment(PROJECT, new UserId("dave"), ProjectRole.CAN_VIEW));
        JsonNode prefixes = setting("prefixes");
        assertThat(prefixes.get("wh").asString()).isEqualTo("http://example.org/wh#");
        assertThat(prefixes.get("ex").asString()).isEqualTo("http://example.org/ex#");
        JsonNode entity = setting("new-entity");
        assertThat(entity.get("iriPrefix").asString()).isEqualTo("http://example.org/wh#");
        assertThat(entity.get("kit").get("type").asString()).isEqualTo("SUPPLIED_NAME");
        assertThat(entity.get("kit").get("whiteSpace").asString()).isEqualTo("UNDERSCORES");
        assertThat(entity.get("labelProperty").asString()).isEqualTo("http://www.w3.org/2000/01/rdf-schema#label");
        assertThat(entity.get("defaultLang").asString()).isEqualTo("en");
        JsonNode names = setting("display-names");
        assertThat(names).hasSize(2);
        assertThat(names.get(0).get("lang").asString()).isEqualTo("en");
        assertThat(names.get(1).get("annotationProperty").asString())
                .isEqualTo("http://www.w3.org/2004/02/skos/core#prefLabel");
        assertThat(names.get(1).get("lang").asString()).isEqualTo("de");
        ProjectDetails details = projects.find(PROJECT).orElseThrow();
        assertThat(details.displayName()).isEqualTo("Warehouse");
        assertThat(details.description()).isEqualTo("Boxes");
        assertThat(details.owner()).isEqualTo(new UserId("alice"));
        assertThat(details.inTrash()).isTrue();
        assertThat(details.createdAt()).isEqualTo(Instant.ofEpochMilli(1_690_000_000_000L));
        assertThat(details.createdBy()).isEqualTo(new UserId("alice"));
        assertThat(details.modifiedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(details.modifiedBy()).isEqualTo(new UserId("bob"));
        assertThat(summary.warnings()).anyMatch(warning -> warning.contains("MysteryRole"));
        assertThat(summary.warnings()).anyMatch(warning -> warning.contains("LocalName"));
        assertThat(summary.warnings()).noneMatch(warning -> warning.contains(SECRET));
        assertThat(setting("new-entity").toString()).doesNotContain(SECRET);
        assertThat(summary.usersCreated()).isEqualTo(3);
    }

    @Test
    void unknown_kit_warns_and_falls_back_to_uuid(@TempDir Path temp) throws IOException {
        Path packaged = copyPackage(samplePackage(), temp.resolve("package"));
        Path projectDir = packaged.resolve("projects").resolve(PROJECT.toString());
        ObjectNode manifest = (ObjectNode) JSON.readTree(projectDir.resolve("manifest.json"));
        ObjectNode crud = JSON.createObjectNode();
        crud.set("raw", JSON.readTree("""
                {"settings":{"prefixSettings":{"iriPrefix":"http://example.org/custom#"},
                  "suffixSettings":{"_class":"CustomKit"}}}
                """));
        manifest.set("entityCrudKitSettings", crud);
        Files.writeString(projectDir.resolve("manifest.json"), JSON.writeValueAsString(manifest));
        rewriteChecksum(projectDir, "manifest.json", sha256(projectDir.resolve("manifest.json")));

        MigrationSummary summary = migrator.importPackage(packaged, false);

        JsonNode entity = setting("new-entity");
        assertThat(entity.get("kit").get("type").asString()).isEqualTo("UUID");
        assertThat(entity.get("iriPrefix").asString()).isEqualTo("http://example.org/custom#");
        assertThat(summary.warnings()).anyMatch(warning -> warning.contains("CustomKit"));
    }

    @Test
    void converts_uuid_obo_and_supplied_name_documents() {
        LegacySettingsConverter converter = new LegacySettingsConverter();
        OntologyKey target = OntologyKey.of(1);

        ConvertedSettings uuid = converter.convert(JSON.readTree("""
                {"settings":{"prefixSettings":{"iriPrefix":"http://example.org/id/"},
                  "suffixSettings":{"_class":"edu.stanford.bmir.protege.web.shared.crud.uuid.UUIDSuffixSettings",
                    "uuidFormat":"BASE62"}}}
                """), JSON.readTree("{}"), target);
        assertThat(uuid.warnings()).isEmpty();
        assertThat(JSON.readTree(uuid.newEntityJson()).get("kit").get("type").asString())
                .isEqualTo("UUID");

        ConvertedSettings standard = converter.convert(JSON.readTree("""
                {"suffixSettings":{"_class":"Uuid","uuidFormat":"STANDARD"},
                  "prefixSettings":{"iriPrefix":"http://example.org/id/"}}
                """), JSON.readTree("{}"), target);
        assertThat(standard.warnings()).anyMatch(warning -> warning.contains("STANDARD"));
        assertThat(JSON.readTree(standard.newEntityJson())
                        .get("kit")
                        .get("type")
                        .asString())
                .isEqualTo("UUID");

        ConvertedSettings dashes = converter.convert(JSON.readTree("""
                {"suffixSettings":{"_class":"SuppliedName","whiteSpaceTreatment":"REPLACE_WITH_DASHES"},
                  "prefixSettings":{"iriPrefix":"http://example.org/sn#"}}
                """), JSON.readTree("{}"), target);
        assertThat(dashes.warnings()).anyMatch(warning -> warning.contains("REPLACE_WITH_DASHES"));
        assertThat(JSON.readTree(dashes.newEntityJson())
                        .get("kit")
                        .get("whiteSpace")
                        .asString())
                .isEqualTo("CAMEL_CASE");

        ConvertedSettings obo = converter.convert(JSON.readTree("""
                {"settings":{"prefixSettings":{"iriPrefix":"http://purl.obolibrary.org/obo/WH_"},
                  "suffixSettings":{"_class":"edu.stanford.bmir.protege.web.shared.crud.oboid.OBOIdSuffixSettings",
                    "totalDigits":5,
                    "userIdRanges":[{"userId":"alice","start":10,"end":20},
                      {"userId":"alice","start":30,"end":40}]}}}
                """), JSON.readTree("{}"), target);
        JsonNode kit = JSON.readTree(obo.newEntityJson()).get("kit");
        assertThat(kit.get("type").asString()).isEqualTo("OBO");
        assertThat(kit.get("totalDigits").asInt()).isEqualTo(5);
        assertThat(kit.get("userRanges")).hasSize(1);
        assertThat(kit.get("userRanges").get(0).get("userId").asString()).isEqualTo("alice");
        assertThat(kit.get("userRanges").get(0).get("start").asLong()).isEqualTo(10L);
        assertThat(kit.get("userRanges").get(0).get("end").asLong()).isEqualTo(20L);
        assertThat(obo.warnings()).anyMatch(warning -> warning.contains("duplicate"));

        ConvertedSettings broken = converter.convert(JSON.readTree("""
                {"suffixSettings":{"_class":"OboId","totalDigits":0},
                  "prefixSettings":{"iriPrefix":"http://purl.obolibrary.org/obo/WH_"}}
                """), JSON.readTree("{}"), target);
        assertThat(JSON.readTree(broken.newEntityJson())
                        .get("kit")
                        .get("totalDigits")
                        .asInt())
                .isEqualTo(7);
        assertThat(broken.warnings()).isNotEmpty();

        ConvertedSettings extended = converter.convert(JSON.readTree("""
                {"suffixSettings":{"_class":"OboId",
                   "totalDigits":{"$numberInt":"5"},
                   "userIdRanges":[{"userId":"alice","start":{"$numberInt":"10"},
                     "end":{"$numberLong":"9223372036854775807"}}]},
                  "prefixSettings":{"iriPrefix":"http://purl.obolibrary.org/obo/WH_"}}
                """), JSON.readTree("{}"), target);
        JsonNode extendedKit = JSON.readTree(extended.newEntityJson()).get("kit");
        assertThat(extended.warnings()).isEmpty();
        assertThat(extendedKit.get("totalDigits").asInt()).isEqualTo(5);
        assertThat(extendedKit.get("userRanges").get(0).get("end").asLong()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void imports_users_when_the_package_has_no_projects(@TempDir Path temp) throws IOException {
        Files.createDirectories(temp.resolve("projects"));
        Files.writeString(temp.resolve("users.json"), """
                [{"username":"carol","realName":"Carol","email":"carol@example.com"}]
                """);

        MigrationSummary summary = migrator.importPackage(temp, false);

        assertThat(summary.projects()).isZero();
        assertThat(summary.usersCreated()).isEqualTo(1);
        assertThat(account("carol").displayName()).isEqualTo("Carol");
        assertThat(account("carol").passwordResetRequired()).isTrue();
    }

    private void writeUsers(Path packaged) throws IOException {
        Files.writeString(packaged.resolve("users.json"), """
                [
                  {"username":"alice","realName":"Alice","email":"alice@example.com"},
                  {"username":"bob","realName":"Bobby","email":"bob@new.example","password":"%s","salt":"c2FsdA=="},
                  {"username":"carol","realName":"Carol","email":"carol@example.com","passwordHash":"%s"}
                ]
                """.formatted(SECRET, SECRET));
    }

    private static void patchManifest(Path projectDir) throws IOException {
        ObjectNode manifest = (ObjectNode) JSON.readTree(projectDir.resolve("manifest.json"));
        ObjectNode details = (ObjectNode) manifest.get("details");
        details.put("displayName", "Warehouse");
        details.put("description", "Boxes");
        details.put("owner", "alice");
        details.put("inTrash", true);
        details.put("createdAt", 1_690_000_000_000L);
        details.put("createdBy", "alice");
        details.put("modifiedAt", 1_700_000_000_000L);
        details.put("modifiedBy", "bob");
        manifest.set("prefixes", JSON.readTree("""
                {"wh":"http://example.org/wh#","ex":"http://example.org/ex#"}
                """));
        manifest.set("roleAssignments", JSON.readTree("""
                [
                  {"username":"bob","roles":["CAN_VIEW","CAN_EDIT"]},
                  {"username":"carol","roles":["CAN_MANAGE","MysteryRole"]},
                  {"username":"dave","roles":["CAN_VIEW"]}
                ]
                """));
        ObjectNode crud = JSON.createObjectNode();
        crud.set("raw", JSON.readTree("""
                {"settings":{"prefixSettings":{"iriPrefix":"http://example.org/wh#","conditionalIriPrefixes":[]},
                  "suffixSettings":{"_class":"SuppliedName","whiteSpaceTreatment":"REPLACE_WITH_UNDERSCORES"}}}
                """));
        manifest.set("entityCrudKitSettings", crud);
        ObjectNode display = JSON.createObjectNode();
        display.set("raw", JSON.readTree("""
                {"primaryDisplayNameLanguages":[
                   {"type":"AnnotationAssertion","propertyIri":"rdfs:label","lang":"EN"},
                   {"type":"LocalName"}],
                 "secondaryDisplayNameLanguages":[
                   {"type":"AnnotationAssertion","propertyIri":"http://www.w3.org/2004/02/skos/core#prefLabel","lang":"de"}]}
                """));
        manifest.set("displayNameSettings", display);
        Files.writeString(projectDir.resolve("manifest.json"), JSON.writeValueAsString(manifest));
        rewriteChecksum(projectDir, "manifest.json", sha256(projectDir.resolve("manifest.json")));
    }

    private JsonNode setting(String key) {
        String stored = jdbc.sql("""
                        select value::text
                        from project_settings
                        where project_id = ? and key = ?
                        """)
                .param(PROJECT.value())
                .param(key)
                .query((rs, row) -> rs.getString(1))
                .single();
        return JSON.readTree(stored);
    }

    private String passwordColumn(String userId) {
        return jdbc.sql("select password_hash from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> Optional.ofNullable(rs.getString("password_hash")))
                .single()
                .orElse(null);
    }

    private UserAccount account(String userId) {
        return users.findById(new UserId(userId)).orElseThrow();
    }

    private void deleteProject(ProjectId projectId) {
        jdbc.sql("delete from outbox where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from revision_subjects where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from revisions where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from snapshots where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from role_assignments where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from project_settings where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from projects where project_id = ?")
                .param(projectId.value())
                .update();
    }

    private void deleteUserIfUnreferenced(String userId) {
        jdbc.sql("""
                        delete from users
                        where user_id = ?
                          and not exists (select 1 from projects where owner = users.user_id)
                          and not exists (select 1 from api_keys where user_id = users.user_id)
                          and not exists (select 1 from role_assignments where user_id = users.user_id)
                        """).param(userId).update();
    }

    private static Path samplePackage() {
        Path fromModule = Path.of("..", "..", "testdata", "migration", "sample-package");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        return Path.of("testdata", "migration", "sample-package");
    }

    private static Path copyPackage(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest);
                }
            }
        }
        return target;
    }

    private static void rewriteChecksum(Path projectDir, String name, String hash) throws IOException {
        Path sums = projectDir.resolve("SHA256SUMS");
        String updated = Files.readString(sums, StandardCharsets.UTF_8)
                .lines()
                .map(line -> line.endsWith("  " + name) ? hash + "  " + name : line)
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        Files.writeString(sums, updated + "\n", StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
