package wprebuild.app.ontology;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Lists the direct named individuals of one class. Requires {@code VIEW_PROJECT}.
 *
 * <p>A direct individual has a class assertion of {@code cls} itself. Individuals of a subclass are
 * not included. {@code page} is zero-based.
 */
public record ListIndividualsByType(ProjectId projectId, IRI cls, int page, int size)
        implements Command<IndividualPage> {

    public static final int MAX_SIZE = 200;

    public ListIndividualsByType {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(cls, "class");
        if (!cls.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be from 1 to " + MAX_SIZE);
        }
    }
}
