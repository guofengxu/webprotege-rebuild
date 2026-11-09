package wprebuild.app.migration;

import java.util.List;
import org.springframework.boot.ExitCodeGenerator;

/**
 * IC-1 through IC-8, or a project that already exists. The message contains each rule and location.
 * Startup maps this to a non-zero process status.
 */
public final class InvalidMigrationPackageException extends RuntimeException implements ExitCodeGenerator {

    private final List<MigrationFailure> failures;

    public InvalidMigrationPackageException(List<MigrationFailure> failures) {
        super(message(failures));
        this.failures = List.copyOf(failures);
    }

    public List<MigrationFailure> failures() {
        return failures;
    }

    @Override
    public int getExitCode() {
        return 1;
    }

    private static String message(List<MigrationFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            throw new IllegalArgumentException("migration failures are required");
        }
        StringBuilder text = new StringBuilder();
        for (MigrationFailure failure : failures) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(failure.rule())
                    .append(" at ")
                    .append(failure.location())
                    .append(": ")
                    .append(failure.detail());
        }
        return text.toString();
    }
}
