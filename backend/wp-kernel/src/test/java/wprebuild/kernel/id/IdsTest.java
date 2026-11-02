package wprebuild.kernel.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    private static final String PROJECT_UUID = "0d8f03d4-d9bb-496d-a78c-146868af8265";

    @Test
    void project_id_accepts_a_uuid_and_prints_it() {
        ProjectId projectId = ProjectId.of(PROJECT_UUID);

        assertThat(projectId.value()).isEqualTo(UUID.fromString(PROJECT_UUID));
        assertThat(projectId.toString()).isEqualTo(PROJECT_UUID);
    }

    @Test
    void project_id_canonicalises_letter_case() {
        ProjectId projectId = ProjectId.of(PROJECT_UUID.toUpperCase());

        assertThat(projectId.toString()).isEqualTo(PROJECT_UUID);
        assertThat(projectId).isEqualTo(ProjectId.of(PROJECT_UUID));
    }

    @Test
    void project_id_rejects_a_malformed_uuid() {
        assertThatThrownBy(() -> ProjectId.of("wrong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wrong");
    }

    @Test
    void project_id_rejects_null() {
        assertThatThrownBy(() -> ProjectId.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void project_ids_with_the_same_uuid_are_equal() {
        ProjectId left = ProjectId.of(PROJECT_UUID);
        ProjectId right = ProjectId.of(PROJECT_UUID);

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
        assertThat(left).isNotEqualTo(null);
        assertThat(left).isNotEqualTo(ProjectId.of("d16a6ca0-3afc-4af3-8a95-82cdc82f52cc"));
    }

    @Test
    void project_id_random_returns_a_uuid() {
        ProjectId projectId = ProjectId.random();

        assertThat(projectId.value()).isNotNull();
        assertThat(projectId.toString()).isEqualTo(projectId.value().toString());
        assertThat(ProjectId.random()).isNotEqualTo(projectId);
    }

    @Test
    void revision_zero_means_an_empty_project() {
        assertThat(RevisionNumber.ZERO.value()).isZero();
        assertThat(RevisionNumber.ZERO.isZero()).isTrue();
        assertThat(RevisionNumber.of(0)).isEqualTo(RevisionNumber.ZERO);
        assertThat(RevisionNumber.of(1).isZero()).isFalse();
    }

    @Test
    void revision_number_rejects_a_negative_value() {
        assertThatThrownBy(() -> RevisionNumber.of(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    void revision_number_next_increments_by_one() {
        assertThat(RevisionNumber.ZERO.next()).isEqualTo(RevisionNumber.of(1));
        assertThat(RevisionNumber.of(5).next()).isEqualTo(RevisionNumber.of(6));
    }

    @Test
    void revision_number_next_rejects_overflow() {
        assertThatThrownBy(() -> RevisionNumber.of(Long.MAX_VALUE).next()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revision_numbers_sort_and_report_which_is_later() {
        RevisionNumber zero = RevisionNumber.ZERO;
        RevisionNumber one = RevisionNumber.of(1);
        RevisionNumber two = RevisionNumber.of(2);

        assertThat(zero).isLessThan(one);
        assertThat(two).isGreaterThan(one);
        assertThat(one.compareTo(RevisionNumber.of(1))).isZero();
        assertThat(two.isAfter(one)).isTrue();
        assertThat(one.isAfter(two)).isFalse();
        assertThat(one.isAfter(one)).isFalse();
        assertThat(List.of(two, zero, one).stream().sorted().toList()).containsExactly(zero, one, two);
    }

    @Test
    void revision_numbers_with_the_same_value_are_equal() {
        RevisionNumber left = RevisionNumber.of(4);
        RevisionNumber right = RevisionNumber.of(4);

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    @Test
    void revision_number_is_after_rejects_null() {
        assertThatThrownBy(() -> RevisionNumber.of(1).isAfter(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void user_id_accepts_a_name_and_exposes_the_system_user() {
        UserId userId = new UserId("alice");

        assertThat(userId.value()).isEqualTo("alice");
        assertThat(UserId.SYSTEM.value()).isEqualTo("system");
        assertThat(new UserId("alice")).isEqualTo(userId);
        assertThat(new UserId("alice").hashCode()).isEqualTo(userId.hashCode());
        assertThat(new UserId("bob")).isNotEqualTo(userId);
    }

    @Test
    void user_id_keeps_surrounding_whitespace_when_the_name_is_not_blank() {
        assertThat(new UserId("  alice  ").value()).isEqualTo("  alice  ");
    }

    @Test
    void user_id_rejects_null_empty_and_blank() {
        assertThatThrownBy(() -> new UserId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new UserId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserId("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
