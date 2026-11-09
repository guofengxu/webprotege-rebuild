package wprebuild.app.migration;

import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Imports {@code wp.migrate.package} during startup. A failed import aborts startup so the process
 * status is non-zero. An empty property leaves the server alone.
 */
@Component
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final ProjectMigrator migrator;

    private final String packageDirectory;

    private final boolean replace;

    public MigrationRunner(
            ProjectMigrator migrator,
            @Value("${wp.migrate.package:}") String packageDirectory,
            @Value("${wp.migrate.replace:false}") boolean replace) {
        this.migrator = Objects.requireNonNull(migrator, "migrator");
        this.packageDirectory = packageDirectory == null ? "" : packageDirectory.strip();
        this.replace = replace;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (packageDirectory.isEmpty()) {
            return;
        }
        Path directory = Path.of(packageDirectory);
        try {
            MigrationSummary summary = migrator.importPackage(directory, replace);
            log.info(
                    "migration imported {} projects, {} revisions, {} new users from {}",
                    summary.projects(),
                    summary.revisions(),
                    summary.usersCreated(),
                    directory);
        } catch (InvalidMigrationPackageException ex) {
            log.error("migration failed: {}", ex.getMessage(), ex);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("migration failed", ex);
            throw new MigrationStartupFailure(ex);
        }
    }
}

/** Startup failure that is not already an {@link InvalidMigrationPackageException}. Exit status is 1. */
final class MigrationStartupFailure extends RuntimeException implements ExitCodeGenerator {

    MigrationStartupFailure(RuntimeException cause) {
        super(cause.getMessage() == null ? "migration failed" : cause.getMessage(), cause);
    }

    @Override
    public int getExitCode() {
        return 1;
    }
}
