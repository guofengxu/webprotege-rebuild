package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.kernel.id.RevisionNumber;

/** A frame written by an update, plus the project head after the command. */
public record UpdatedFrame<F>(F frame, RevisionNumber revision) {

    public UpdatedFrame {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(revision, "revision");
    }

    /** JSON body for the web module. {@code revision} is the head number, not a nested object. */
    public Body<F> body() {
        return new Body<>(frame, revision.value());
    }

    /** HTTP representation of {@link UpdatedFrame}. */
    public record Body<F>(F frame, long revision) {

        public Body {
            Objects.requireNonNull(frame, "frame");
        }
    }
}
