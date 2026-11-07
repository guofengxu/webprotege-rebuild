package wprebuild.migrator.legacy;

import java.util.List;
import java.util.Objects;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;

/** One revision from a legacy {@code change-data.binary} log. */
public record LegacyRevision(
        long revision, String username, long timestamp, String description, List<OWLOntologyChangeRecord> records) {

    public LegacyRevision {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(description, "description");
        records = List.copyOf(Objects.requireNonNull(records, "records"));
    }
}
