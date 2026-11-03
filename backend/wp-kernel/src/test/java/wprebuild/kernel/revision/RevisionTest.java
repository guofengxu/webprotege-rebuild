package wprebuild.kernel.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

class RevisionTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    void rejects_revision_zero() {
        assertThatThrownBy(() -> revision(RevisionNumber.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
    }

    @Test
    void copies_the_change_list() {
        OntologyChange declare = new DeclareOntology(OntologyKey.of(1), Optional.empty(), Optional.empty());
        List<OntologyChange> changes = new ArrayList<>();
        changes.add(declare);

        Revision revision = revision(RevisionNumber.of(1), changes);
        changes.clear();

        assertThat(revision.changes()).containsExactly(declare);
        assertThatThrownBy(() -> revision.changes().add(declare)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Revision revision(RevisionNumber number, List<OntologyChange> changes) {
        return new Revision(number, UserId.SYSTEM, TIMESTAMP, "desc", changes);
    }
}
