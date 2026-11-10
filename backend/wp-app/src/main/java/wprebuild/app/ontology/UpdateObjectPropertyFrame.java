package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.ObjectPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/**
 * Replaces the editable part of one object-property frame. Requires {@code EDIT_ONTOLOGY}.
 *
 * <p>{@code from} is the frame the client edited. The handler rejects the command when the stored
 * frame no longer matches it.
 */
public record UpdateObjectPropertyFrame(ProjectId projectId, ObjectPropertyFrame from, ObjectPropertyFrame to)
        implements Command<UpdatedFrame<ObjectPropertyFrame>> {

    public UpdateObjectPropertyFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        FrameSubjects.requireSame(
                from.subject(), to.subject(), "OBJECT_PROPERTY", "object property", "an object property");
    }

    /** JSON body of {@code PUT /api/projects/{id}/frames/object-property}. */
    public record Request(ObjectPropertyFrame from, ObjectPropertyFrame to) {}
}
