package wprebuild.kernel.runtime;

import java.util.List;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Read model derived from the project ontologies, such as a hierarchy or display names.
 *
 * <p>{@link #rebuild(ProjectOntologies)} runs once when a runtime is loaded. {@link #onApplied}
 * runs after each committed revision has been applied.
 */
public interface DerivedView {

    void rebuild(ProjectOntologies ontologies);

    void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after);
}
