package wprebuild.app.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.jdbc.core.simple.JdbcClient;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;
import wprebuild.app.project.ProjectRepository;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnLines;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

/** Imports {@code testdata/migration/sample-package} and checks IC-7, IC-8, and rollback. */
@DatabaseTest
class ProjectMigratorTest {

    private static final ProjectId PROJECT = ProjectId.of("2f0b8a6e-6c1e-4c2d-9a51-0b5e0c3c1d11");

    private static final Instant ALICE_AT = Instant.ofEpochMilli(1_690_000_000_000L);

    private static final Instant BOB_AT = Instant.ofEpochMilli(1_690_000_001_000L);

    private final ProjectMigrator migrator;

    private final RevisionLog revisions;

    private final ProjectRepository projects;

    private final UserService users;

    private final JdbcClient jdbc;

    ProjectMigratorTest(
            ProjectMigrator migrator,
            RevisionLog revisions,
            ProjectRepository projects,
            UserService users,
            JdbcClient jdbc) {
        this.migrator = migrator;
        this.revisions = revisions;
        this.projects = projects;
        this.users = users;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void removeSampleProject() {
        deleteProject(PROJECT);
        deleteUserIfUnreferenced("alice");
        deleteUserIfUnreferenced("bob");
    }

    @Test
    void imports_sample_history_with_original_users_and_timestamps() {
        boolean aliceMissing = users.findById(new UserId("alice")).isEmpty();
        boolean bobMissing = users.findById(new UserId("bob")).isEmpty();

        migrator.importPackage(samplePackage(), false);

        assertThat(revisions.head(PROJECT)).isEqualTo(RevisionNumber.of(2));
        Revision first = revisions.get(PROJECT, RevisionNumber.of(1)).orElseThrow();
        Revision second = revisions.get(PROJECT, RevisionNumber.of(2)).orElseThrow();
        assertThat(first.user()).isEqualTo(new UserId("alice"));
        assertThat(first.timestamp()).isEqualTo(ALICE_AT);
        assertThat(first.description()).isEqualTo("Uploaded project");
        assertThat(first.changes())
                .first()
                .isEqualTo(new DeclareOntology(
                        OntologyKey.of(1), Optional.of(IRI.create("http://example.org/wh")), Optional.empty()));
        assertThat(second.user()).isEqualTo(new UserId("bob"));
        assertThat(second.timestamp()).isEqualTo(BOB_AT);
        assertThat(second.description()).isEqualTo("Name the box");
        assertThat(projects.find(PROJECT)).isPresent();
        if (aliceMissing) {
            assertThat(account("alice").passwordResetRequired()).isTrue();
        }
        if (bobMissing) {
            assertThat(account("bob").passwordResetRequired()).isTrue();
        }
    }

    @Test
    void repeated_import_without_replace_is_rejected() {
        Path sample = samplePackage();
        migrator.importPackage(sample, false);

        assertThatThrownBy(() -> migrator.importPackage(sample, false))
                .isInstanceOf(InvalidMigrationPackageException.class)
                .hasMessageContaining("already exists");
        assertThat(revisions.head(PROJECT)).isEqualTo(RevisionNumber.of(2));
    }

    @Test
    void replace_reimports_the_same_history() {
        Path sample = samplePackage();
        migrator.importPackage(sample, false);

        migrator.importPackage(sample, true);

        assertThat(revisions.head(PROJECT)).isEqualTo(RevisionNumber.of(2));
        assertThat(countRevisions()).isEqualTo(2);
    }

    @Test
    void tampered_head_fails_ic7_and_leaves_no_rows(@TempDir Path temp) throws IOException {
        boolean aliceBefore = users.findById(new UserId("alice")).isPresent();
        Path packaged = copyPackage(samplePackage(), temp.resolve("package"));
        tamperHead(packaged);

        assertThatThrownBy(() -> migrator.importPackage(packaged, false))
                .isInstanceOf(InvalidMigrationPackageException.class)
                .hasMessageContaining("IC-7");
        assertThat(projects.find(PROJECT)).isEmpty();
        assertThat(countRevisions()).isZero();
        assertThat(countRevisionSubjects()).isZero();
        assertThat(countOutbox()).isZero();
        assertThat(countSnapshots()).isZero();
        if (!aliceBefore) {
            assertThat(users.findById(new UserId("alice"))).isEmpty();
        }
    }

    @Test
    void removed_content_still_matches_an_empty_head(@TempDir Path temp) throws IOException {
        Path head = temp.resolve("head.ofn-lines.gz");
        gzip(head, "");
        OntologyKey key = OntologyKey.of(1);
        RegisteredOntology ontology =
                new RegisteredOntology(key, Optional.of(IRI.create("http://example.org/wh")), Optional.empty());
        OntologyChange added = OfnLines.decodeLine(
                "+\tAXIOM\to1\tDeclaration(Class(<http://example.org/wh#Pallet>))", OWLManager.getOWLDataFactory());
        MigrationProject project = new MigrationProject(
                PROJECT,
                "projects/" + PROJECT,
                "Warehouse",
                "",
                "alice",
                false,
                ALICE_AT,
                "alice",
                BOB_AT,
                "bob",
                List.of(ontology),
                List.of(),
                head);

        assertThat(HeadEquivalence.check(List.of(ontology.declare(), added, added.inverse()), project))
                .isEmpty();
    }

    @Test
    void revision_below_one_reports_ic3(@TempDir Path temp) throws IOException {
        Path packaged = copyPackage(samplePackage(), temp.resolve("package"));
        Path projectDir = packaged.resolve("projects").resolve(PROJECT.toString());
        Path revisions = projectDir.resolve("revisions.jsonl");
        Files.writeString(revisions, Files.readString(revisions).replace("\"rev\":1", "\"rev\":0"));
        rewriteChecksum(projectDir, "revisions.jsonl", sha256(revisions));

        assertThatThrownBy(() -> new MigrationPackageReader().read(packaged))
                .isInstanceOf(InvalidMigrationPackageException.class)
                .hasMessageContaining("IC-3");
    }

    private UserAccount account(String userId) {
        return users.findById(new UserId(userId)).orElseThrow();
    }

    private int countRevisions() {
        return count("select count(*) from revisions where project_id = ?");
    }

    private int countRevisionSubjects() {
        return count("select count(*) from revision_subjects where project_id = ?");
    }

    private int countOutbox() {
        return count("select count(*) from outbox where project_id = ?");
    }

    private int countSnapshots() {
        return count("select count(*) from snapshots where project_id = ?");
    }

    private int count(String sql) {
        return jdbc.sql(sql)
                .param(PROJECT.value())
                .query((rs, row) -> rs.getInt(1))
                .single();
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

    /** Changes one class IRI. Axiom type counts stay the same, so the failure is IC-7. */
    private static void tamperHead(Path packaged) throws IOException {
        Path projectDir = packaged.resolve("projects").resolve(PROJECT.toString());
        Path head = projectDir.resolve("head.ofn-lines.gz");
        String text = gunzip(head).replaceFirst("wh#Pallet", "wh#Crate");
        gzip(head, text);
        rewriteChecksum(projectDir, "head.ofn-lines.gz", sha256(head));
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

    private static String gunzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void gzip(Path file, String text) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
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
