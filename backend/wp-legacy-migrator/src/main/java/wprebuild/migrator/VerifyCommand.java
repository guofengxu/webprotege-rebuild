package wprebuild.migrator;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import wprebuild.migrator.export.PackageVerifier;

@Command(name = "verify", description = "Check a migration package against IC-1 through IC-6.")
public final class VerifyCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--package", required = true, paramLabel = "<dir>", description = "Migration package directory")
    private Path packageDir;

    @Override
    public Integer call() {
        try {
            List<PackageVerifier.Failure> failures = new PackageVerifier().check(packageDir);
            for (PackageVerifier.Failure failure : failures) {
                spec.commandLine()
                        .getErr()
                        .println(failure.rule() + " " + failure.location() + ": " + failure.detail());
            }
            return failures.isEmpty() ? 0 : 1;
        } catch (UncheckedIOException exception) {
            spec.commandLine().getErr().println(exception.getMessage());
            return 1;
        }
    }
}
