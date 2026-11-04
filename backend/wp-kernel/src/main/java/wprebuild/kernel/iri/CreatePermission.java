package wprebuild.kernel.iri;

import org.semanticweb.owlapi.model.EntityType;
import wprebuild.kernel.id.UserId;

/** Whether a user may mint an entity of a given type (BS-05). */
@FunctionalInterface
public interface CreatePermission {

    boolean canCreate(UserId user, EntityType<?> type);

    CreatePermission ALLOW_ALL = (user, type) -> true;
}
