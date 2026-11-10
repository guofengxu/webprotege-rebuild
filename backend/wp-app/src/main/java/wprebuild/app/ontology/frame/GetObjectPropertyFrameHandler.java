package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.GetObjectPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads an object-property frame under the project read lock. */
@Service
public final class GetObjectPropertyFrameHandler
        extends AbstractPropertyFrameReadHandler<GetObjectPropertyFrame, ObjectPropertyFrame> {

    private final PropertyFrameQuery frames;

    public GetObjectPropertyFrameHandler(PropertyFrameQuery frames) {
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<GetObjectPropertyFrame> commandType() {
        return GetObjectPropertyFrame.class;
    }

    @Override
    protected ProjectId projectId(GetObjectPropertyFrame command) {
        return command.projectId();
    }

    @Override
    protected ObjectPropertyFrame read(GetObjectPropertyFrame command) {
        return frames.objectFrame(command.projectId(), command.iri());
    }
}
