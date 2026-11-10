package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wprebuild.app.access.Action;
import wprebuild.app.bus.Command;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.ProjectSettingsProvider;
import wprebuild.app.ontology.UpdatedFrame;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeListGenerator;

/** Applies a property-frame edit and returns the frame read after the commit. */
abstract class AbstractPropertyFrameUpdateHandler<C extends Command<UpdatedFrame<F>>, F>
        implements CommandHandler<C, UpdatedFrame<F>> {

    private static final Logger log = LoggerFactory.getLogger(AbstractPropertyFrameUpdateHandler.class);

    private final ProjectRuntimeRegistry registry;

    private final ProjectSettingsProvider settings;

    protected AbstractPropertyFrameUpdateHandler(ProjectRuntimeRegistry registry, ProjectSettingsProvider settings) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Optional<ProjectId> project(C command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(projectId(command));
    }

    @Override
    public Optional<Action> requiredAction(C command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_ONTOLOGY);
    }

    @Override
    public UpdatedFrame<F> handle(C command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectId projectId = projectId(command);
        ProjectHandle handle = registry.get(projectId);
        OntologyKey target = settings.newEntitySettings(projectId).targetOntology();
        ChangeApplicationResult<F> applied =
                handle.pipeline().apply(context.user(), generator(command, handle, target));
        RevisionNumber revision = applied.revision().map(Revision::number).orElseGet(() -> head(handle));
        F frame = read(projectId, subject(command));
        log.info("updated {} {} in project {} at revision {}", kind(), subject(command), projectId, revision.value());
        return new UpdatedFrame<>(frame, revision);
    }

    protected abstract ProjectId projectId(C command);

    protected abstract ChangeListGenerator<F> generator(C command, ProjectHandle handle, OntologyKey target);

    protected abstract F read(ProjectId projectId, IRI iri);

    protected abstract IRI subject(C command);

    protected abstract String kind();

    private static RevisionNumber head(ProjectHandle handle) {
        return handle.gate().read(() -> handle.runtime().head());
    }
}
