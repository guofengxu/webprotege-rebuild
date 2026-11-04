package wprebuild.kernel.crud;

import java.util.Optional;
import org.semanticweb.owlapi.model.EntityType;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;

/** Mints one entity IRI and the changes that declare it (BS-06). */
public interface EntityCrudKit {

    CreatedEntity create(
            EntityType<?> type,
            String suppliedName,
            Optional<String> lang,
            NewEntitySettings settings,
            PrefixedNameExpander expander);
}
