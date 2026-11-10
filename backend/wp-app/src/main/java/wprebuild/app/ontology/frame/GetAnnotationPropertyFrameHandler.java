package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.GetAnnotationPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads an annotation-property frame under the project read lock. */
@Service
public final class GetAnnotationPropertyFrameHandler
        extends AbstractPropertyFrameReadHandler<GetAnnotationPropertyFrame, AnnotationPropertyFrame> {

    private final PropertyFrameQuery frames;

    public GetAnnotationPropertyFrameHandler(PropertyFrameQuery frames) {
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<GetAnnotationPropertyFrame> commandType() {
        return GetAnnotationPropertyFrame.class;
    }

    @Override
    protected ProjectId projectId(GetAnnotationPropertyFrame command) {
        return command.projectId();
    }

    @Override
    protected AnnotationPropertyFrame read(GetAnnotationPropertyFrame command) {
        return frames.annotationFrame(command.projectId(), command.iri());
    }
}
