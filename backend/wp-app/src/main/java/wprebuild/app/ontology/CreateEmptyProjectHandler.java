package wprebuild.app.ontology;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.project.ProjectRepository;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Writes the project row, grants the creator {@code CAN_MANAGE}, then commits revision 1.
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Service
public class CreateEmptyProjectHandler implements CommandHandler<CreateEmptyProject, ProjectId> {

    private static final Logger log = LoggerFactory.getLogger(CreateEmptyProjectHandler.class);

    private final ProjectRepository projects;

    private final AccessService access;

    private final ProjectRuntimeRegistry registry;

    private final ProjectProperties properties;

    public CreateEmptyProjectHandler(
            ProjectRepository projects,
            AccessService access,
            ProjectRuntimeRegistry registry,
            ProjectProperties properties) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.access = Objects.requireNonNull(access, "access");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Class<CreateEmptyProject> commandType() {
        return CreateEmptyProject.class;
    }

    @Override
    public Optional<ProjectId> project(CreateEmptyProject command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(CreateEmptyProject command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.CREATE_EMPTY_PROJECT);
    }

    @Override
    @Transactional
    public ProjectId handle(CreateEmptyProject command, CommandContext context) {
        ProjectId projectId = ProjectId.random();
        evictUnlessCommitted(projectId);
        try {
            Instant now = Instant.now();
            UserId user = context.user();
            projects.insert(new ProjectDetails(
                    projectId, command.name(), command.description(), user, false, now, user, now, user));
            access.grantCreator(projectId, user);
            IRI ontologyIri = command.ontologyIri().orElseGet(() -> properties.ontologyIriFor(projectId));
            submitInitialRevision(projectId, ontologyIri, user);
            log.info("created empty project {}", projectId);
            return projectId;
        } catch (RuntimeException failure) {
            registry.evict(projectId);
            throw failure;
        }
    }

    private void evictUnlessCommitted(ProjectId projectId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    registry.evict(projectId);
                }
            }
        });
    }

    private void submitInitialRevision(ProjectId projectId, IRI ontologyIri, UserId user) {
        ChangeApplicationResult<ProjectId> applied =
                registry.get(projectId).pipeline().apply(user, new InitialDeclaration(projectId, ontologyIri));
        if (applied.revision().isEmpty()) {
            throw new IllegalStateException("empty project did not create a revision");
        }
    }

    private static final class InitialDeclaration implements ChangeListGenerator<ProjectId> {

        private final ProjectId projectId;

        private final DeclareOntology declaration;

        private InitialDeclaration(ProjectId projectId, IRI ontologyIri) {
            this.projectId = projectId;
            this.declaration = new DeclareOntology(OntologyKey.of(1), Optional.of(ontologyIri), Optional.empty());
        }

        @Override
        public GeneratedChanges<ProjectId> generate(ChangeContext context) {
            Objects.requireNonNull(context, "context");
            return new GeneratedChanges<>(List.of(declaration), projectId);
        }

        @Override
        public String describe(GeneratedChanges<ProjectId> generated, List<OntologyChange> effective) {
            Objects.requireNonNull(generated, "generated changes");
            Objects.requireNonNull(effective, "effective changes");
            return "Created empty project";
        }
    }
}
