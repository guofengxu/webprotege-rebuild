package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/**
 * Creates one entity per name and places it under {@code parents} (BS-07).
 *
 * <p>The required action is {@code CREATE_CLASS}, {@code CREATE_PROPERTY}, or
 * {@code CREATE_INDIVIDUAL}, chosen from {@code entityType}.
 */
public record CreateEntities(
        ProjectId projectId, EntityType<?> entityType, List<String> names, Optional<String> lang, Set<IRI> parents)
        implements Command<CreateEntities.Result> {

    public CreateEntities {
        Objects.requireNonNull(projectId, "project id");
        EntityActions.create(entityType);
        names = List.copyOf(Objects.requireNonNull(names, "names"));
        Objects.requireNonNull(lang, "language");
        parents = Set.copyOf(Objects.requireNonNull(parents, "parents"));
        if (names.isEmpty()) {
            throw new IllegalArgumentException("names must not be empty");
        }
    }

    /** Created entities, in request order, and the project head after the command. */
    public record Result(List<EntityNode> entities, RevisionNumber revision) {

        public Result {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            Objects.requireNonNull(revision, "revision");
        }

        /** JSON body for the web module. Keeps {@link EntityNode} inside this module. */
        public Body body() {
            return new Body(entities, revision.value());
        }
    }

    /** HTTP representation of {@link Result}. {@code revision} is the head number, not a nested object. */
    @Schema(name = "CreatedEntities")
    public record Body(List<EntityNode> entities, long revision) {

        public Body {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        }
    }
}
