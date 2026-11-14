package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ProjectionBenchmark#nextReportPath(Path, String)} (P6-18).
 *
 * <p>Callers: {@code ProjectionBenchmark.writeReport} (formal report naming) and this class.
 * No production data — {@code @TempDir} files hold synthetic markers {@code first}/{@code second}.
 */
class ProjectionReportPathTest {

    @TempDir
    Path dir;

    @Test
    void nextReportPath_uses_base_name_when_absent() {
        Path path = ProjectionBenchmark.nextReportPath(dir, "projection-poc-2026-11-14.md");
        assertThat(path).isEqualTo(dir.resolve("projection-poc-2026-11-14.md"));
    }

    @Test
    void nextReportPath_uses_suffix_2_when_base_exists() throws IOException {
        Files.writeString(dir.resolve("projection-poc-2026-11-14.md"), "first");
        Path path = ProjectionBenchmark.nextReportPath(dir, "projection-poc-2026-11-14.md");
        assertThat(path).isEqualTo(dir.resolve("projection-poc-2026-11-14-2.md"));
    }

    @Test
    void nextReportPath_uses_suffix_3_when_base_and_2_exist() throws IOException {
        Files.writeString(dir.resolve("projection-poc-2026-11-14.md"), "first");
        Files.writeString(dir.resolve("projection-poc-2026-11-14-2.md"), "second");
        Path path = ProjectionBenchmark.nextReportPath(dir, "projection-poc-2026-11-14.md");
        assertThat(path).isEqualTo(dir.resolve("projection-poc-2026-11-14-3.md"));
    }
}
