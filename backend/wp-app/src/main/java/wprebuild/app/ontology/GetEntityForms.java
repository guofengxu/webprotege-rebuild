package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Loads applicable entity forms with projected values and the current head (BS-17 / P6-69).
 *
 * <p>GateGuard facts: called by {@code EntityFormsController} and {@code GetEntityFormsHandler};
 * Glob found none; no data files; user asked {@code 完成下一个任务卡}.
 */
public record GetEntityForms(ProjectId projectId, IRI entity, Set<UUID> formIds) implements Command<EntityFormsResult> {

    public GetEntityForms {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(entity, "entity");
        if (!entity.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        formIds = formIds == null ? Set.of() : Set.copyOf(formIds);
    }

    public GetEntityForms(ProjectId projectId, IRI entity) {
        this(projectId, entity, Set.of());
    }
}
