package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.ProjectSettingsProvider;
import wprebuild.app.ontology.UpdateObjectPropertyFrame;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.runtime.ChangeListGenerator;

/** Applies an object-property frame edit. */
@Service
public final class UpdateObjectPropertyFrameHandler
        extends AbstractPropertyFrameUpdateHandler<UpdateObjectPropertyFrame, ObjectPropertyFrame> {

    private final PropertyFrameQuery frames;

    public UpdateObjectPropertyFrameHandler(
            ProjectRuntimeRegistry registry, ProjectSettingsProvider settings, PropertyFrameQuery frames) {
        super(registry, settings);
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<UpdateObjectPropertyFrame> commandType() {
        return UpdateObjectPropertyFrame.class;
    }

    @Override
    protected ProjectId projectId(UpdateObjectPropertyFrame command) {
        return command.projectId();
    }

    @Override
    protected ChangeListGenerator<ObjectPropertyFrame> generator(
            UpdateObjectPropertyFrame command, ProjectHandle handle, OntologyKey target) {
        return new UpdateObjectPropertyFrameGenerator(command.from(), command.to(), frames, handle.runtime(), target);
    }

    @Override
    protected ObjectPropertyFrame read(ProjectId projectId, IRI iri) {
        return frames.objectFrame(projectId, iri);
    }

    @Override
    protected IRI subject(UpdateObjectPropertyFrame command) {
        return IRI.create(command.to().subject().iri());
    }

    @Override
    protected String kind() {
        return "object property";
    }
}
