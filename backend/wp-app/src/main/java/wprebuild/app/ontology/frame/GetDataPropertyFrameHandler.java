package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.GetDataPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads a data-property frame under the project read lock. */
@Service
public final class GetDataPropertyFrameHandler
        extends AbstractPropertyFrameReadHandler<GetDataPropertyFrame, DataPropertyFrame> {

    private final PropertyFrameQuery frames;

    public GetDataPropertyFrameHandler(PropertyFrameQuery frames) {
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<GetDataPropertyFrame> commandType() {
        return GetDataPropertyFrame.class;
    }

    @Override
    protected ProjectId projectId(GetDataPropertyFrame command) {
        return command.projectId();
    }

    @Override
    protected DataPropertyFrame read(GetDataPropertyFrame command) {
        return frames.dataFrame(command.projectId(), command.iri());
    }
}
