package wprebuild.kernel.iri;

import java.util.List;
import java.util.Objects;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * Mints one real entity for a fresh IRI. The UUID, supplied-name, and OBO strategies are P1-13
 * and P1-14; this card only defines the port.
 */
@FunctionalInterface
public interface EntityCreator {

    CreatedEntity create(EntityType<?> type, FreshEntityIri fresh, UserId user, ProjectOntologies ontologies);

    /** The minted entity and the changes that declare it (and its label, when the strategy adds one). */
    record CreatedEntity(OWLEntity entity, List<OntologyChange> creationChanges) {

        public CreatedEntity {
            Objects.requireNonNull(entity, "entity");
            if (FreshEntityIri.isFresh(entity.getIRI())) {
                throw new IllegalArgumentException("created entity must not use a fresh IRI: " + entity.getIRI());
            }
            creationChanges = List.copyOf(Objects.requireNonNull(creationChanges, "creation changes"));
        }
    }
}
