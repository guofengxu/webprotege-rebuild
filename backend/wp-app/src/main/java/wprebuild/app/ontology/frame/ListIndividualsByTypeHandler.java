package wprebuild.app.ontology.frame;

import java.util.Objects;
import org.springframework.stereotype.Service;
import wprebuild.app.ontology.IndividualPage;
import wprebuild.app.ontology.ListIndividualsByType;
import wprebuild.kernel.id.ProjectId;

/** Lists direct individuals of a class under the project read lock. */
@Service
public final class ListIndividualsByTypeHandler
        extends AbstractPropertyFrameReadHandler<ListIndividualsByType, IndividualPage> {

    private final IndividualFrameQuery frames;

    public ListIndividualsByTypeHandler(IndividualFrameQuery frames) {
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Class<ListIndividualsByType> commandType() {
        return ListIndividualsByType.class;
    }

    @Override
    protected ProjectId projectId(ListIndividualsByType command) {
        return command.projectId();
    }

    @Override
    protected IndividualPage read(ListIndividualsByType command) {
        return frames.individuals(command.projectId(), command.cls(), command.page(), command.size());
    }
}
