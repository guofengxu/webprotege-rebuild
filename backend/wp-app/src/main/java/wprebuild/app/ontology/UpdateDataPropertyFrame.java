package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.DataPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/**
 * Replaces the editable part of one data-property frame. Requires {@code EDIT_ONTOLOGY}.
 *
 * <p>{@code from} is the frame the client edited. The handler rejects the command when the stored
 * frame no longer matches it.
 */
public record UpdateDataPropertyFrame(ProjectId projectId, DataPropertyFrame from, DataPropertyFrame to)
        implements Command<UpdatedFrame<DataPropertyFrame>> {

    public UpdateDataPropertyFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        FrameSubjects.requireSame(from.subject(), to.subject(), "DATA_PROPERTY", "data property", "a data property");
    }

    /** JSON body of {@code PUT /api/projects/{id}/frames/data-property}. */
    @Schema(name = "UpdateDataPropertyFrameRequest")
    public record Request(DataPropertyFrame from, DataPropertyFrame to) {}
}
