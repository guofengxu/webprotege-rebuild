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

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    public ProjectMigrator(
            MigrationPackageReader reader,
            ProjectRepository projects,
            RevisionLog revisions,
            UserService users,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.users = Objects.requireNonNull(users, "users");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    public MigrationSummary importPackage(Path packageDirectory, boolean replace) {
        MigrationPackage loaded = reader.read(packageDirectory);
        int projectCount = 0;
        int revisionCount = 0;
        int usersCreated = 0;
        for (MigrationProject project : loaded.projects()) {
            Stored stored = transactions.execute(status -> importProject(project, replace));
            if (stored == null) {
                throw new IllegalStateException("import returned no result for " + project.projectId());
            }
            projectCount++;
            revisionCount += stored.revisions();
            usersCreated += stored.usersCreated();
            log.info(
                    "imported project {} at revision {} ({} new users)",
                    project.projectId(),
                    stored.revisions(),
                    stored.usersCreated());
        }
        return new MigrationSummary(projectCount, revisionCount, usersCreated);
    }

    private Stored importProject(MigrationProject project, boolean replace) {
        ProjectId projectId = project.projectId();
        if (projects.find(projectId).isPresent()) {
            if (!replace) {
                throw new InvalidMigrationPackageException(
                        List.of(new MigrationFailure("project", project.location(), "project already exists")));
            }
            deleteProject(projectId);
        }
        int created = ensureUsers(project);
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
        return new Stored(history.size(), created);
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
        created += ensureUser(actor(project.owner()).value());
        created += ensureUser(actor(project.createdBy()).value());
        created += ensureUser(actor(project.modifiedBy()).value());
        return created;
    }

    /** Missing accounts cannot sign in until a password is set. Profiles are filled by P3-11. */
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
     * The sample manifest leaves both blank. P3-11 overwrites the details from the same manifest.
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

    private record Stored(int revisions, int usersCreated) {}
}
