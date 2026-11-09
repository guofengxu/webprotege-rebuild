package wprebuild.app.ontology;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.app.access.Action;

/** Maps an OWL entity type to the create and delete actions in BS-10. */
public final class EntityActions {

    private EntityActions() {}

    public static EntityType<?> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("entity type is required");
        }
        return switch (raw.strip()) {
            case "CLASS" -> EntityType.CLASS;
            case "OBJECT_PROPERTY" -> EntityType.OBJECT_PROPERTY;
            case "DATA_PROPERTY" -> EntityType.DATA_PROPERTY;
            case "ANNOTATION_PROPERTY" -> EntityType.ANNOTATION_PROPERTY;
            case "NAMED_INDIVIDUAL" -> EntityType.NAMED_INDIVIDUAL;
            default ->
                throw new IllegalArgumentException("entity type must be CLASS, OBJECT_PROPERTY, DATA_PROPERTY,"
                        + " ANNOTATION_PROPERTY, or NAMED_INDIVIDUAL");
        };
    }

    public static Action create(EntityType<?> type) {
        return action(type, true);
    }

    public static Action delete(EntityType<?> type) {
        return action(type, false);
    }

    /** One delete action per distinct entity type. Properties share {@link Action#DELETE_PROPERTY}. */
    public static Set<Action> deleteAll(Collection<? extends OWLEntity> entities) {
        Objects.requireNonNull(entities, "entities");
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        for (OWLEntity entity : entities) {
            actions.add(delete(Objects.requireNonNull(entity, "entity").getEntityType()));
        }
        return Set.copyOf(actions);
    }

    private static Action action(EntityType<?> type, boolean create) {
        Objects.requireNonNull(type, "entity type");
        if (EntityType.CLASS.equals(type)) {
            return create ? Action.CREATE_CLASS : Action.DELETE_CLASS;
        }
        if (EntityType.OBJECT_PROPERTY.equals(type)
                || EntityType.DATA_PROPERTY.equals(type)
                || EntityType.ANNOTATION_PROPERTY.equals(type)) {
            return create ? Action.CREATE_PROPERTY : Action.DELETE_PROPERTY;
        }
        if (EntityType.NAMED_INDIVIDUAL.equals(type)) {
            return create ? Action.CREATE_INDIVIDUAL : Action.DELETE_INDIVIDUAL;
        }
        throw new IllegalArgumentException("unsupported entity type: " + type.getPrintName());
    }
}
