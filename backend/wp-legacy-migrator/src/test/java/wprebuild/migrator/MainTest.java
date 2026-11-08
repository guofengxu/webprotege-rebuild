package wprebuild.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.semanticweb.binaryowl.BinaryOWLOntologyChangeLog;
import org.semanticweb.owlapi.apibinding.OWLManager;

class MainTest {

    @Test
    void help_exits_zero_and_lists_subcommands() {
        Capture capture = run("--help");

        assertThat(capture.exitCode()).isZero();
        assertThat(capture.out()).contains("inspect", "export", "verify");
    }

    @Test
    void version_contains_project_version() {
        Capture capture = run("--version");

        assertThat(capture.exitCode()).isZero();
        assertThat(capture.out()).contains("0.1.0");
    }

    @Test
    void verify_requires_package() {
        Capture capture = run("verify");

        assertThat(capture.exitCode()).isEqualTo(2);
        assertThat(capture.err()).contains("--package");
    }

    @Test
    void export_requires_data_dir() {
        Capture capture = run("export");

        assertThat(capture.exitCode()).isEqualTo(2);
        assertThat(capture.err()).contains("--data-dir");
    }

    @Test
    void inspect_requires_data_dir() {
        Capture capture = run("inspect");

        assertThat(capture.exitCode()).isEqualTo(2);
        assertThat(capture.err()).contains("--data-dir");
    }

    @Test
    void classpath_loads_owlapi4_and_binaryowl() {
        assertThat(OWLManager.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toExternalForm())
                .contains("owlapi-distribution-4.5.13");
        assertThat(BinaryOWLOntologyChangeLog.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toExternalForm())
                .contains("binaryowl-2.0.1");
        assertThat(OWLManager.createOWLOntologyManager()).isNotNull();
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.execute(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int exitCode, String out, String err) {}
}
