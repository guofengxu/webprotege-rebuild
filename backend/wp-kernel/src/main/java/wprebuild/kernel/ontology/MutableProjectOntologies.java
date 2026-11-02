package wprebuild.kernel.ontology;

import java.util.List;
import wprebuild.kernel.change.OntologyChange;

/**
 * Applies changes in list order with no effectiveness filter (that filter is P1-05, BS-02).
 *
 * <p>An edit of an unregistered key throws {@link IllegalStateException}. Declaring a key that
 * already exists throws {@link IllegalStateException}. {@code AddImport} records the import
 * declaration and does not load the imported document.
 */
public interface MutableProjectOntologies extends ProjectOntologies {

    void apply(List<OntologyChange> changes);
}
