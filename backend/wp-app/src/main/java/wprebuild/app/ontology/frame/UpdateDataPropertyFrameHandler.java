package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.ProjectSettingsProvider;
import wprebuild.app.ontology.UpdateDataPropertyFrame;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.runtime.ChangeListGenerator;

/** Applies a data-property frame edit. */
@Service
public final class UpdateDataPropertyFrameHandler
        extends AbstractPropertyFrameUpdateHandler<UpdateDataPropertyFrame, DataPropertyFrame> {

    private final PropertyFrameQuery frames;

    public UpdateDataPropertyFrameHandler(
            ProjectRuntimeRegistry registry, ProjectSettingsProvider settings, PropertyFrameQuery frames) {
        super(registry, settings);
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<UpdateDataPropertyFrame> commandType() {
        return UpdateDataPropertyFrame.class;
    }

    @Override
    protected ProjectId projectId(UpdateDataPropertyFrame command) {
        return command.projectId();
    }

    @Override
    protected ChangeListGenerator<DataPropertyFrame> generator(
            UpdateDataPropertyFrame command, ProjectHandle handle, OntologyKey target) {
        return new UpdateDataPropertyFrameGenerator(command.from(), command.to(), frames, handle.runtime(), target);
    }

    @Override
    protected DataPropertyFrame read(ProjectId projectId, IRI iri) {
        return frames.dataFrame(projectId, iri);
    }

    @Override
    protected IRI subject(UpdateDataPropertyFrame command) {
        return IRI.create(command.to().subject().iri());
    }

    @Override
    protected String kind() {
        return "data property";
    }
}
