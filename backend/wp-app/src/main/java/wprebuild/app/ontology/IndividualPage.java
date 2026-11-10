package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import wprebuild.app.ontology.dto.EntityNode;

/** One page of named individuals. {@code page} is zero-based. {@code total} counts every match. */
public record IndividualPage(List<EntityNode> individuals, int page, int size, long total) {

    public IndividualPage {
        individuals = List.copyOf(Objects.requireNonNull(individuals, "individuals"));
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (total < individuals.size()) {
            throw new IllegalArgumentException("total is smaller than the page");
        }
    }
}
