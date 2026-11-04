package wprebuild.kernel.iri;

import java.util.Objects;
import org.semanticweb.owlapi.model.EntityType;
import wprebuild.kernel.id.UserId;

/** The user is not allowed to mint an entity of this type (BS-05). */
public final class CreatePermissionDeniedException extends RuntimeException {

    private final UserId user;

    private final EntityType<?> entityType;

    public CreatePermissionDeniedException(UserId user, EntityType<?> entityType) {
        super(message(user, entityType));
        this.user = user;
        this.entityType = entityType;
    }

    public UserId user() {
        return user;
    }

    public EntityType<?> entityType() {
        return entityType;
    }

    private static String message(UserId user, EntityType<?> entityType) {
        return "user " + Objects.requireNonNull(user, "user").value() + " cannot create "
                + Objects.requireNonNull(entityType, "entity type");
    }
}
