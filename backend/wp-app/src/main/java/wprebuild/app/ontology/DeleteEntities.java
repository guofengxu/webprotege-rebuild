package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/**
 * Deletes every entity whose IRI is in {@code iris} (BS-07).
 *
 * <p>The bus requires {@code VIEW_PROJECT}. The handler then requires {@code DELETE_CLASS},
 * {@code DELETE_PROPERTY}, or {@code DELETE_INDIVIDUAL} for each entity type in the snapshot it
 * deletes. A mixed set is checked one type at a time.
 */
public record DeleteEntities(ProjectId projectId, Set<IRI> iris) implements Command<RevisionNumber> {

    public DeleteEntities {
        Objects.requireNonNull(projectId, "project id");
        iris = Set.copyOf(Objects.requireNonNull(iris, "iris"));
        if (iris.isEmpty()) {
            throw new IllegalArgumentException("iris must not be empty");
        }
    }
}
