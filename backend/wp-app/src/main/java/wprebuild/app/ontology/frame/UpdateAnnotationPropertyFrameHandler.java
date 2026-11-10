package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.ProjectSettingsProvider;
import wprebuild.app.ontology.UpdateAnnotationPropertyFrame;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.runtime.ChangeListGenerator;

/** Applies an annotation-property frame edit. */
@Service
public final class UpdateAnnotationPropertyFrameHandler
        extends AbstractPropertyFrameUpdateHandler<UpdateAnnotationPropertyFrame, AnnotationPropertyFrame> {

    private final PropertyFrameQuery frames;

    public UpdateAnnotationPropertyFrameHandler(
            ProjectRuntimeRegistry registry, ProjectSettingsProvider settings, PropertyFrameQuery frames) {
        super(registry, settings);
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<UpdateAnnotationPropertyFrame> commandType() {
        return UpdateAnnotationPropertyFrame.class;
    }

    @Override
    protected ProjectId projectId(UpdateAnnotationPropertyFrame command) {
        return command.projectId();
    }

    @Override
    protected ChangeListGenerator<AnnotationPropertyFrame> generator(
            UpdateAnnotationPropertyFrame command, ProjectHandle handle, OntologyKey target) {
        return new UpdateAnnotationPropertyFrameGenerator(
                command.from(), command.to(), frames, handle.runtime(), target);
    }

    @Override
    protected AnnotationPropertyFrame read(ProjectId projectId, IRI iri) {
        return frames.annotationFrame(projectId, iri);
    }

    @Override
    protected IRI subject(UpdateAnnotationPropertyFrame command) {
        return IRI.create(command.to().subject().iri());
    }

    @Override
    protected String kind() {
        return "annotation property";
    }
}
