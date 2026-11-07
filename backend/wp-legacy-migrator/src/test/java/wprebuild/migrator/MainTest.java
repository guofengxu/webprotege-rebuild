package wprebuild.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    void subcommands_report_not_implemented() {
        for (String name : List.of("inspect", "export", "verify")) {
            Capture capture = run(name);

            assertThat(capture.exitCode()).as(name).isEqualTo(2);
            assertThat(capture.out()).as(name).contains("not implemented");
        }
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
