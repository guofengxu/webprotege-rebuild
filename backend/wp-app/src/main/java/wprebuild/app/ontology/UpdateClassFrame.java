package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.ontology.frame.ClassFrame;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/**
 * Replaces the editable part of one class frame. Requires {@code EDIT_ONTOLOGY}.
 *
 * <p>{@code from} is the frame the client edited. The handler rejects the command when the stored
 * frame no longer matches it.
 */
public record UpdateClassFrame(ProjectId projectId, ClassFrame from, ClassFrame to)
        implements Command<UpdateClassFrame.Result> {

    public UpdateClassFrame {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.subject().iri().equals(to.subject().iri())) {
            throw new IllegalArgumentException("from and to must describe the same class");
        }
        requireClass(from.subject());
        requireClass(to.subject());
        if (!IRI.create(from.subject().iri()).isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
    }

    /** JSON body of {@code PUT /api/projects/{id}/frames/class}. The project id stays in the path. */
    @Schema(name = "UpdateClassFrameRequest")
    public record Request(ClassFrame from, ClassFrame to) {}

    /** Updated frame and the project head after the command. */
    public record Result(ClassFrame frame, RevisionNumber revision) {

        public Result {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(revision, "revision");
        }

        /** JSON body for the web module. {@code revision} is the head number, not a nested object. */
        public Body body() {
            return new Body(frame, revision.value());
        }
    }

    /** HTTP representation of {@link Result}. */
    public record Body(ClassFrame frame, long revision) {

        public Body {
            Objects.requireNonNull(frame, "frame");
        }
    }

    private static void requireClass(EntityNode subject) {
        if (!"CLASS".equals(subject.entityType())) {
            throw new IllegalArgumentException("frame subject must be a class");
        }
    }
}
