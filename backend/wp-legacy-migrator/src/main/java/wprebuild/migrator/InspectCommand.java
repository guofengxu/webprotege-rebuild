package wprebuild.migrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.semanticweb.owlapi.model.OWLOntologyID;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import wprebuild.migrator.legacy.LegacyDataDir;
import wprebuild.migrator.legacy.LegacyProjectLocation;
import wprebuild.migrator.legacy.LegacyReadException;
import wprebuild.migrator.legacy.LegacyRevision;
import wprebuild.migrator.legacy.LegacyRevisionReader;

@Command(
        name = "inspect",
        description =
                "Summarise legacy projects. Each line is '<projectId> <revisionCount> <headRevision|-> <changeCount> <ontologyCount>'.")
public final class InspectCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--data-dir", required = true, paramLabel = "<dir>", description = "Legacy data.directory")
    private Path dataDir;

    @Override
    public Integer call() {
        try {
            LegacyRevisionReader reader = new LegacyRevisionReader();
            List<String> lines = new ArrayList<>();
            for (LegacyProjectLocation project : new LegacyDataDir(dataDir).projects()) {
                lines.add(summarise(reader, project));
            }
            for (String line : lines) {
                spec.commandLine().getOut().println(line);
            }
            return 0;
        } catch (LegacyReadException | IllegalArgumentException exception) {
            spec.commandLine().getErr().println(errorText(exception));
            return 1;
        }
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

    private static String summarise(LegacyRevisionReader reader, LegacyProjectLocation project) {
        if (Files.notExists(project.changeDataBinary())) {
            return line(project.projectId(), 0, "-", 0, 0);
        }
        Totals totals = new Totals();
        reader.read(project.changeDataBinary(), totals::add);
        return line(project.projectId(), totals.revisions, totals.head(), totals.changes, totals.ontologies.size());
    }

    private static String line(String projectId, int revisions, String head, int changes, int ontologies) {
        return projectId + " " + revisions + " " + head + " " + changes + " " + ontologies;
    }

    private static final class Totals {
        private int revisions;
        private int changes;
        private long headRevision;
        private boolean hasHead;
        private final Set<OWLOntologyID> ontologies = new HashSet<>();

        private void add(LegacyRevision revision) {
            revisions++;
            changes += revision.records().size();
            headRevision = revision.revision();
            hasHead = true;
            revision.records().forEach(record -> ontologies.add(record.getOntologyID()));
        }

        private String head() {
            return hasHead ? Long.toString(headRevision) : "-";
        }
    }
}
