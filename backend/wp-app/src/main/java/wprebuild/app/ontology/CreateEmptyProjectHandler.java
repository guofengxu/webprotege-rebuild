package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
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

    private final ProjectProvisioning projects;

    private final ProjectRuntimeRegistry registry;

    private final ProjectProperties properties;

    public CreateEmptyProjectHandler(
            ProjectProvisioning projects, ProjectRuntimeRegistry registry, ProjectProperties properties) {
        this.projects = Objects.requireNonNull(projects, "projects");
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
        UserId user = context.user();
        ProjectId projectId = projects.insert(command.name(), command.description(), user);
        try {
            IRI ontologyIri = command.ontologyIri().orElseGet(() -> properties.ontologyIriFor(projectId));
            submitInitialRevision(projectId, ontologyIri, user);
            log.info("created empty project {}", projectId);
            return projectId;
        } catch (RuntimeException failure) {
            registry.evict(projectId);
            throw failure;
        }
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
