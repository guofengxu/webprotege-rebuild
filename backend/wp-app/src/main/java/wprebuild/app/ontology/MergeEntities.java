package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.app.bus.Command;
import wprebuild.app.ontology.dto.EntityRef;
import wprebuild.kernel.generator.MergedEntityTreatment;
import wprebuild.kernel.id.ProjectId;

/**
 * Merges source entities into a target entity (BS-13). Entity types are resolved from ontology
 * declarations by {@link wprebuild.kernel.generator.MergeEntitiesGenerator}.
 */
public record MergeEntities(
        ProjectId projectId, Set<IRI> sources, IRI target, MergedEntityTreatment treatment, String description)
        implements Command<MergeEntities.Result> {

    public static final int MAX_SOURCES = 100;

    public MergeEntities {
        Objects.requireNonNull(projectId, "project id");
        sources = copySources(sources);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(treatment, "treatment");
        description = description == null ? "" : description;
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        if (sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("sources must contain at most " + MAX_SOURCES + " IRIs");
        }
    }

    /** Target entity and the new revision number, or {@code null} revision when nothing changed. */
    public record Result(EntityRef target, Long revision) {

        public Result {
            Objects.requireNonNull(target, "target");
        }

        public Body body() {
            return new Body(target, revision);
        }
    }

    /** HTTP representation of {@link Result}. */
    @Schema(name = "MergeEntitiesResult")
    public record Body(EntityRef target, Long revision) {

        public Body {
            Objects.requireNonNull(target, "target");
        }
    }

    private static Set<IRI> copySources(Set<IRI> sources) {
        Objects.requireNonNull(sources, "sources");
        Set<IRI> copied = new LinkedHashSet<>();
        for (IRI source : sources) {
            copied.add(Objects.requireNonNull(source, "source"));
        }
        return Set.copyOf(copied);
    }
}
