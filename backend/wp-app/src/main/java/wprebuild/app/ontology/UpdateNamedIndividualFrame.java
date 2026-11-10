package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.NamedIndividualFrame;
import wprebuild.kernel.id.ProjectId;

/**
 * Replaces the editable part of one named-individual frame. Requires {@code EDIT_ONTOLOGY}.
 *
 * <p>{@code from} is the frame the client edited. The handler rejects the command when the stored
 * frame no longer matches it.
 */
public record UpdateNamedIndividualFrame(ProjectId projectId, NamedIndividualFrame from, NamedIndividualFrame to)
        implements Command<UpdatedFrame<NamedIndividualFrame>> {

    public UpdateNamedIndividualFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        FrameSubjects.requireSame(
                from.subject(), to.subject(), "NAMED_INDIVIDUAL", "named individual", "a named individual");
    }

    /** JSON body of {@code PUT /api/projects/{id}/frames/individual}. */
    @Schema(name = "UpdateNamedIndividualFrameRequest")
    public record Request(NamedIndividualFrame from, NamedIndividualFrame to) {}
}
