package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.ProjectSettingsProvider;
import wprebuild.app.ontology.UpdateClassFrame;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;

/** Applies a class-frame edit and returns the frame read after the commit. */
@Service
public final class UpdateClassFrameHandler implements CommandHandler<UpdateClassFrame, UpdateClassFrame.Result> {

    private static final Logger log = LoggerFactory.getLogger(UpdateClassFrameHandler.class);

    private final ProjectRuntimeRegistry registry;

    private final ProjectSettingsProvider settings;

    private final ClassFrameQuery frames;

    public UpdateClassFrameHandler(
            ProjectRuntimeRegistry registry, ProjectSettingsProvider settings, ClassFrameQuery frames) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<UpdateClassFrame> commandType() {
        return UpdateClassFrame.class;
    }

    @Override
    public Optional<ProjectId> project(UpdateClassFrame command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(UpdateClassFrame command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_ONTOLOGY);
    }

    @Override
    public UpdateClassFrame.Result handle(UpdateClassFrame command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectHandle handle = registry.get(command.projectId());
        OntologyKey target = settings.newEntitySettings(command.projectId()).targetOntology();
        ChangeApplicationResult<ClassFrame> applied = handle.pipeline()
                .apply(
                        context.user(),
                        new UpdateClassFrameGenerator(command.from(), command.to(), frames, handle.runtime(), target));
        RevisionNumber revision = applied.revision().map(Revision::number).orElseGet(() -> head(handle));
        ClassFrame frame = frames.frame(
                command.projectId(), IRI.create(command.to().subject().iri()));
        log.info(
                "updated class {} in project {} at revision {}",
                command.to().subject().iri(),
                command.projectId(),
                revision.value());
        return new UpdateClassFrame.Result(frame, revision);
    }

    private static RevisionNumber head(ProjectHandle handle) {
        return handle.gate().read(() -> handle.runtime().head());
    }
}
