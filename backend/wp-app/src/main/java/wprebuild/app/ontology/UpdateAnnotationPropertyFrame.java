package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.frame.AnnotationPropertyFrame;
import wprebuild.kernel.id.ProjectId;

/**
 * Replaces the editable part of one annotation-property frame. Requires {@code EDIT_ONTOLOGY}.
 *
 * <p>{@code from} is the frame the client edited. The handler rejects the command when the stored
 * frame no longer matches it.
 */
public record UpdateAnnotationPropertyFrame(
        ProjectId projectId, AnnotationPropertyFrame from, AnnotationPropertyFrame to)
        implements Command<UpdatedFrame<AnnotationPropertyFrame>> {

    public UpdateAnnotationPropertyFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        FrameSubjects.requireSame(
                from.subject(), to.subject(), "ANNOTATION_PROPERTY", "annotation property", "an annotation property");
    }

    /** JSON body of {@code PUT /api/projects/{id}/frames/annotation-property}. */
    @Schema(name = "UpdateAnnotationPropertyFrameRequest")
    public record Request(AnnotationPropertyFrame from, AnnotationPropertyFrame to) {}
}
