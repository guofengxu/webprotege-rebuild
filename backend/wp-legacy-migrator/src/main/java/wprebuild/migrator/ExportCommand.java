package wprebuild.migrator;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import wprebuild.migrator.export.ProjectExporter;
import wprebuild.migrator.legacy.LegacyReadException;
import wprebuild.migrator.mongo.MongoMetadata;

@Command(name = "export", description = "Write a migration package from legacy data.")
public final class ExportCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--data-dir", required = true, paramLabel = "<dir>", description = "Legacy data.directory")
    private Path dataDir;

    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Directory for the migration package")
    private Path out;

    @Option(
            names = "--project",
            paramLabel = "<id>",
            description = "Export one project id. Omit to export every project.")
    private String project;

    @Option(
            names = "--mongo-export-dir",
            paramLabel = "<dir>",
            description = "Directory of mongoexport --jsonArray files. A missing file is a warning.")
    private Path mongoExportDir;

    @Override
    public Integer call() {
        try {
            MongoMetadata metadata = loadMetadata();
            ProjectExporter exporter = new ProjectExporter(Clock.systemUTC());
            if (project == null) {
                exporter.export(dataDir, out, metadata);
            } else {
                exporter.export(dataDir, out, project, metadata);
            }
            return 0;
        } catch (LegacyReadException | IllegalArgumentException | UncheckedIOException exception) {
            spec.commandLine().getErr().println(errorText(exception));
            return 1;
        }
    }

    private MongoMetadata loadMetadata() {
        if (mongoExportDir == null) {
            return MongoMetadata.empty();
        }
        MongoMetadata metadata = MongoMetadata.load(mongoExportDir);
        for (String warning : metadata.warnings()) {
            spec.commandLine().getErr().println("warning: " + warning);
        }
        return metadata;
    }

    private static String errorText(Exception exception) {
        String message = exception.getMessage();
        Throwable cause = exception.getCause();
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return message;
        }
        if (message != null && message.contains(cause.getMessage())) {
            return message;
        }
        return message + ": " + cause.getMessage();
    }
}
