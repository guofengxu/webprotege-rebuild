package wprebuild.app.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.UserService;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.project.ProjectRepository;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

/**
 * Writes a migration package straight into {@link RevisionLog}. The legacy log is authoritative, so
 * this does not go through {@code ChangePipeline}. IC-7 or IC-8 failure rolls the project back.
 */
@Service
public class ProjectMigrator {

    private static final Logger log = LoggerFactory.getLogger(ProjectMigrator.class);

    private final MigrationPackageReader reader;

    private final ProjectRepository projects;

    private final RevisionLog revisions;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roleAssignments;

    private final LegacySettingsConverter settings;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    public ProjectMigrator(
            MigrationPackageReader reader,
            ProjectRepository projects,
            RevisionLog revisions,
            UserService users,
            JdbcRoleAssignmentRepository roleAssignments,
            LegacySettingsConverter settings,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.users = Objects.requireNonNull(users, "users");
        this.roleAssignments = Objects.requireNonNull(roleAssignments, "roleAssignments");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    public MigrationSummary importPackage(Path packageDirectory, boolean replace) {
        MigrationPackage loaded = reader.read(packageDirectory);
        int projectCount = 0;
        int revisionCount = 0;
        int usersCreated = 0;
        List<String> warnings = new ArrayList<>(credentialWarnings(loaded.users()));
        warnings.forEach(warning -> log.warn("migration warning: {}", warning));
        if (loaded.projects().isEmpty()) {
            Integer created = transactions.execute(status -> importListedUsers(loaded.users()));
            return new MigrationSummary(0, 0, created == null ? 0 : created, warnings);
        }
        for (MigrationProject project : loaded.projects()) {
            Stored stored = transactions.execute(status -> importProject(project, loaded.users(), replace));
            if (stored == null) {
                throw new IllegalStateException("import returned no result for " + project.projectId());
            }
            projectCount++;
            revisionCount += stored.revisions();
            usersCreated += stored.usersCreated();
            warnings.addAll(stored.warnings());
            stored.warnings().forEach(warning -> log.warn("migration warning: {}", warning));
            log.info(
                    "imported project {} at revision {} ({} new users)",
                    project.projectId(),
                    stored.revisions(),
                    stored.usersCreated());
        }
        return new MigrationSummary(projectCount, revisionCount, usersCreated, warnings);
    }

    private Stored importProject(MigrationProject project, List<MigrationUser> listedUsers, boolean replace) {
        ProjectId projectId = project.projectId();
        if (projects.find(projectId).isPresent()) {
            if (!replace) {
                throw new InvalidMigrationPackageException(
                        List.of(new MigrationFailure("project", project.location(), "project already exists")));
            }
            deleteProject(projectId);
        }
        int created = importListedUsers(listedUsers);
        created += ensureUsers(project);
        projects.insert(new ProjectDetails(
                projectId,
                displayName(project),
                project.description(),
                actor(project.owner()),
                project.inTrash(),
                project.createdAt(),
                actor(project.createdBy()),
                project.modifiedAt(),
                actor(project.modifiedBy())));
        List<Revision> history = materialize(project);
        RevisionNumber expected = RevisionNumber.ZERO;
        for (Revision revision : history) {
            revisions.append(projectId, expected, revision);
            expected = revision.number();
        }
        List<MigrationFailure> failures = HeadEquivalence.check(readStored(projectId, history.size()), project);
        if (!failures.isEmpty()) {
            throw new InvalidMigrationPackageException(failures);
        }
        List<String> warnings = writeMetadata(project);
        return new Stored(history.size(), created, warnings);
    }

    /**
     * Profiles come from {@code users.json}. An account that already exists is left unchanged,
     * including its password hash. The package never supplies a password.
     */
    private int importListedUsers(List<MigrationUser> listedUsers) {
        int created = 0;
        for (MigrationUser listed : listedUsers) {
            if (users.findById(new UserId(listed.username())).isPresent()) {
                continue;
            }
            String displayName = listed.realName().isBlank() ? listed.username() : listed.realName();
            users.create(listed.username(), displayName, listed.email(), null, List.of());
            created++;
        }
        return created;
    }

    private static List<String> credentialWarnings(List<MigrationUser> listedUsers) {
        List<String> warnings = new ArrayList<>();
        for (MigrationUser listed : listedUsers) {
            if (listed.discardedCredentials()) {
                warnings.add("users.json: ignored password material for " + listed.username());
            }
        }
        return warnings;
    }

    private List<String> writeMetadata(MigrationProject project) {
        String where = project.location() + "/manifest.json";
        List<String> warnings = new ArrayList<>();
        for (String warning : project.metadata().warnings()) {
            warnings.add(where + ": " + warning);
        }
        OntologyKey target = project.ontologies().isEmpty()
                ? OntologyKey.of(1)
                : project.ontologies().get(0).key();
        ConvertedSettings converted = settings.convert(
                project.metadata().crudRaw(), project.metadata().displayRaw(), target);
        for (String warning : converted.warnings()) {
            warnings.add(where + ": " + warning);
        }
        for (ImportedRole role : project.metadata().roles()) {
            roleAssignments.assign(project.projectId(), new UserId(role.username()), role.role());
        }
        insertSetting(
                project.projectId(),
                "prefixes",
                settings.prefixesJson(project.metadata().prefixes()));
        insertSetting(project.projectId(), "new-entity", converted.newEntityJson());
        insertSetting(project.projectId(), "display-names", converted.displayNamesJson());
        return warnings;
    }

    private void insertSetting(ProjectId projectId, String key, String json) {
        int stored =
                jdbc.sql("""
                        insert into project_settings (project_id, key, value)
                        values (?, ?, cast(? as jsonb))
                        """).param(projectId.value()).param(key).param(json).update();
        if (stored != 1) {
            throw new IllegalStateException("project setting was not stored");
        }
    }

    /**
     * Inserts {@link DeclareOntology} at the start of the revision that first mentions each key.
     * The manifest is the registry; the legacy lines do not carry that declaration.
     */
    private static List<Revision> materialize(MigrationProject project) {
        Map<OntologyKey, RegisteredOntology> byKey = new LinkedHashMap<>();
        for (RegisteredOntology ontology : project.ontologies()) {
            byKey.put(ontology.key(), ontology);
        }
        Set<OntologyKey> declared = new HashSet<>();
        List<Revision> history = new ArrayList<>();
        for (MigrationRevision revision : project.revisions()) {
            List<OntologyChange> prefix = new ArrayList<>();
            List<OntologyChange> body = new ArrayList<>();
            for (OntologyChange change : revision.changes()) {
                if (change instanceof DeclareOntology) {
                    declareFromManifest(prefix, declared, byKey, project, revision, change.ontology());
                    continue;
                }
                declareFromManifest(prefix, declared, byKey, project, revision, change.ontology());
                body.add(change);
            }
            List<OntologyChange> changes = new ArrayList<>(prefix.size() + body.size());
            changes.addAll(prefix);
            changes.addAll(body);
            history.add(new Revision(
                    revision.number(), revision.user(), revision.timestamp(), revision.description(), changes));
        }
        return history;
    }

    /** The manifest declaration wins. A declaration already in the legacy line is not stored again. */
    private static void declareFromManifest(
            List<OntologyChange> prefix,
            Set<OntologyKey> declared,
            Map<OntologyKey, RegisteredOntology> byKey,
            MigrationProject project,
            MigrationRevision revision,
            OntologyKey key) {
        if (!declared.add(key)) {
            return;
        }
        RegisteredOntology ontology = byKey.get(key);
        if (ontology == null) {
            throw new InvalidMigrationPackageException(List.of(new MigrationFailure(
                    "IC-5",
                    project.location() + "/revisions.jsonl:rev "
                            + revision.number().value(),
                    "ontology key " + key.value() + " is not registered")));
        }
        prefix.add(ontology.declare());
    }

    private List<OntologyChange> readStored(ProjectId projectId, int count) {
        List<OntologyChange> changes = new ArrayList<>();
        for (int number = 1; number <= count; number++) {
            int current = number;
            RevisionNumber revisionNumber = RevisionNumber.of(current);
            Revision stored = revisions
                    .get(projectId, revisionNumber)
                    .orElseThrow(() -> new IllegalStateException("missing revision " + current));
            changes.addAll(stored.changes());
        }
        return changes;
    }

    private int ensureUsers(MigrationProject project) {
        int created = 0;
        for (String userId : project.userNames()) {
            created += ensureUser(userId);
        }
        for (ImportedRole role : project.metadata().roles()) {
            created += ensureUser(role.username());
        }
        created += ensureUser(actor(project.owner()).value());
        created += ensureUser(actor(project.createdBy()).value());
        created += ensureUser(actor(project.modifiedBy()).value());
        return created;
    }

    /** Missing accounts cannot sign in until a password is set. Profiles come from users.json. */
    private int ensureUser(String userId) {
        UserId id = new UserId(userId);
        if (users.findById(id).isPresent()) {
            return 0;
        }
        users.create(id.value(), id.value(), null, null, List.of());
        return 1;
    }

    /**
     * {@code projects.owner} references {@code users}, and {@code ProjectText} rejects a blank name.
     * The sample manifest leaves both blank. A present manifest value is stored as given.
     */
    private static String displayName(MigrationProject project) {
        if (project.displayName().isBlank()) {
            return project.projectId().toString();
        }
        return project.displayName();
    }

    private static UserId actor(String userId) {
        if (userId == null || userId.isBlank()) {
            return UserId.SYSTEM;
        }
        return new UserId(userId);
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

    private record Stored(int revisions, int usersCreated, List<String> warnings) {}
}
