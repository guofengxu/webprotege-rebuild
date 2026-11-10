package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.GetNamedIndividualFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads a named-individual frame under the project read lock. */
@Service
public final class GetNamedIndividualFrameHandler
        extends AbstractPropertyFrameReadHandler<GetNamedIndividualFrame, NamedIndividualFrame> {

    private final IndividualFrameQuery frames;

    public GetNamedIndividualFrameHandler(IndividualFrameQuery frames) {
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<GetNamedIndividualFrame> commandType() {
        return GetNamedIndividualFrame.class;
    }

    @Override
    protected ProjectId projectId(GetNamedIndividualFrame command) {
        return command.projectId();
    }

    @Override
    protected NamedIndividualFrame read(GetNamedIndividualFrame command) {
        return frames.frame(command.projectId(), command.iri());
    }
}
