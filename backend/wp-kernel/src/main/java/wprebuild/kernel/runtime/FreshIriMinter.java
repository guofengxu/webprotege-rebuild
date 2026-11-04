package wprebuild.kernel.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Replaces temporary IRIs in a generated change list. The real minter is P1-12.
 * {@link #NONE} returns the changes unchanged and an empty rename map.
 */
@FunctionalInterface
public interface FreshIriMinter {

    MintResult mint(UserId user, List<OntologyChange> changes, ProjectOntologies ontologies);

    FreshIriMinter NONE = (user, changes, ontologies) -> MintResult.unchanged(changes);

    /** Changes after minting, and the temporary IRI to minted IRI map. */
    record MintResult(List<OntologyChange> changes, Map<IRI, IRI> renameMap) {

        public MintResult {
            changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
            renameMap = Map.copyOf(Objects.requireNonNull(renameMap, "rename map"));
        }

        public static MintResult unchanged(List<OntologyChange> changes) {
            return new MintResult(changes, Map.of());
        }
    }
}
