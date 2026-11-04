package wprebuild.kernel.runtime;

import java.util.Objects;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.ProjectOntologies;

/** State a {@link ChangeListGenerator} may read while the generation lock is held. */
public record ChangeContext(UserId user, ProjectOntologies ontologies) {

    public ChangeContext {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(ontologies, "ontologies");
    }
}
