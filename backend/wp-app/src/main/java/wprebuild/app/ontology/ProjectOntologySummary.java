package wprebuild.app.ontology;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * One ontology registered in a project. {@code ontologyIri} and {@code versionIri} are {@code null}
 * when the ontology is anonymous or has no version IRI.
 */
public record ProjectOntologySummary(
        String key,
        @Schema(nullable = true) String ontologyIri,
        @Schema(nullable = true) String versionIri,
        long axiomCount) {

    public ProjectOntologySummary {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key is blank");
        }
        if (axiomCount < 0) {
            throw new IllegalArgumentException("axiom count must be non-negative");
        }
    }
}
