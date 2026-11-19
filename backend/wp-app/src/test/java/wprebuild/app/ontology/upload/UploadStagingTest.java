package wprebuild.app.ontology.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import wprebuild.app.ontology.UploadProperties;
import wprebuild.app.shared.ConflictException;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.generator.UploadMergeMode;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Unit tests for owned, TTL-bounded upload staging (P6-27).
 *
 * <p>Gate facts: called only by JUnit; Glob shows only OntologySourceParser in upload/; temp files
 * named as UUID strings with in-memory meta (mode, targetOntology, baseRevision, createdAt ISO-8601);
 * user asked "完成下一个任务卡" (P6-27).
 */
class UploadStagingTest {

    private static final UserId ALICE = new UserId("alice");

    private static final UserId BOB = new UserId("bob");

    private static final ProjectId PROJECT_A = ProjectId.random();

    private static final ProjectId PROJECT_B = ProjectId.random();

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    @TempDir
    Path temp;

    private Path stagingDir;

    private MutableClock clock;

    private UploadStaging staging;

    @BeforeEach
    void setUp() throws IOException {
        stagingDir = temp.resolve("wp-staging");
        Files.createDirectories(stagingDir);
        clock = new MutableClock(T0);
        staging = new UploadStaging(
                new UploadProperties(DataSize.ofMegabytes(200), stagingDir, Duration.ofMinutes(30)), clock, false);
    }

    @Test
    void stages_under_upload_id_without_original_file_name() throws Exception {
        Path source = writeSource("original-name.owl", "content-a");

        UUID uploadId = staging.stage(PROJECT_A, ALICE, source, meta(UploadMergeMode.NEW_VERSION, Optional.empty()));

        Path stored = stagingDir.resolve(uploadId.toString());
        assertThat(stored).exists().hasContent("content-a");
        assertThat(stored.getFileName().toString()).doesNotContain("original");
        assertThat(Files.exists(source)).isFalse();
        assertThat(staging.find(PROJECT_A, ALICE, uploadId)).isPresent();
    }

    @Test
    void find_rejects_other_user_or_project() throws Exception {
        Path source = writeSource("a.owl", "x");
        UUID uploadId = staging.stage(
                PROJECT_A, ALICE, source, meta(UploadMergeMode.ADDITIONS, Optional.of(OntologyKey.of(1))));

        assertThat(staging.find(PROJECT_A, BOB, uploadId)).isEmpty();
        assertThat(staging.find(PROJECT_B, ALICE, uploadId)).isEmpty();
        assertThat(staging.find(PROJECT_A, ALICE, uploadId)).isPresent();
    }

    @Test
    void expired_upload_is_missing_and_file_is_deleted() throws Exception {
        Path source = writeSource("a.owl", "x");
        UUID uploadId = staging.stage(PROJECT_A, ALICE, source, meta(UploadMergeMode.NEW_VERSION, Optional.empty()));
        Path stored = stagingDir.resolve(uploadId.toString());
        assertThat(stored).exists();

        clock.set(T0.plus(Duration.ofMinutes(31)));

        assertThat(staging.find(PROJECT_A, ALICE, uploadId)).isEmpty();
        assertThat(stored).doesNotExist();
    }

    @Test
    void fourth_staging_for_same_user_is_conflict() throws Exception {
        for (int i = 0; i < 3; i++) {
            staging.stage(
                    PROJECT_A,
                    ALICE,
                    writeSource("f" + i + ".owl", "c" + i),
                    meta(UploadMergeMode.NEW_VERSION, Optional.empty()));
        }

        assertThatThrownBy(() -> staging.stage(
                        PROJECT_B,
                        ALICE,
                        writeSource("overflow.owl", "nope"),
                        meta(UploadMergeMode.NEW_VERSION, Optional.empty())))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo(UploadStaging.TOO_MANY_STAGED_UPLOADS));
    }

    @Test
    void discard_removes_file_and_registration() throws Exception {
        UUID uploadId = staging.stage(
                PROJECT_A, ALICE, writeSource("a.owl", "x"), meta(UploadMergeMode.NEW_VERSION, Optional.empty()));
        Path stored = stagingDir.resolve(uploadId.toString());

        staging.discard(PROJECT_A, ALICE, uploadId);

        assertThat(staging.find(PROJECT_A, ALICE, uploadId)).isEmpty();
        assertThat(stored).doesNotExist();
    }

    @Test
    void discard_by_other_user_leaves_file() throws Exception {
        UUID uploadId = staging.stage(
                PROJECT_A, ALICE, writeSource("a.owl", "x"), meta(UploadMergeMode.NEW_VERSION, Optional.empty()));
        Path stored = stagingDir.resolve(uploadId.toString());

        staging.discard(PROJECT_A, BOB, uploadId);

        assertThat(stored).exists();
        assertThat(staging.find(PROJECT_A, ALICE, uploadId)).isPresent();
    }

    @Test
    void stage_stamps_created_at_from_clock_not_caller_meta() throws Exception {
        StagedUploadMeta callerMeta = new StagedUploadMeta(
                UploadMergeMode.NEW_VERSION, Optional.empty(), 1L, Instant.parse("1999-01-01T00:00:00Z"));
        UUID uploadId = staging.stage(PROJECT_A, ALICE, writeSource("a.owl", "x"), callerMeta);

        StagedUpload staged = staging.find(PROJECT_A, ALICE, uploadId).orElseThrow();
        assertThat(staged.meta().createdAt()).isEqualTo(T0);
    }

    @Test
    void cleanup_removes_expired_entries() throws Exception {
        UUID uploadId = staging.stage(
                PROJECT_A, ALICE, writeSource("a.owl", "x"), meta(UploadMergeMode.NEW_VERSION, Optional.empty()));
        Path stored = stagingDir.resolve(uploadId.toString());
        clock.set(T0.plus(Duration.ofMinutes(31)));

        staging.purgeExpired();

        assertThat(staging.find(PROJECT_A, ALICE, uploadId)).isEmpty();
        assertThat(stored).doesNotExist();
    }

    private Path writeSource(String name, String content) throws IOException {
        Path source = temp.resolve(name);
        Files.writeString(source, content);
        return source;
    }

    private StagedUploadMeta meta(UploadMergeMode mode, Optional<OntologyKey> target) {
        return new StagedUploadMeta(mode, target, 1L, clock.instant());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
