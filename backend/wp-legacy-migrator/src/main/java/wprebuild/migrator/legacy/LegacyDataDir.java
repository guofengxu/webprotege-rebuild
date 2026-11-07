package wprebuild.migrator.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Lists legacy project change logs under a WebProtégé {@code data.directory}. */
public final class LegacyDataDir {

    static final String DATA_STORE = "data-store";
    static final String PROJECT_DATA = "project-data";
    static final String CHANGE_DATA_DIRECTORY = "change-data";
    static final String CHANGE_DATA_FILE = "change-data.binary";

    private final Path dataDirectory;

    public LegacyDataDir(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    public List<LegacyProjectLocation> projects() {
        if (!Files.isDirectory(dataDirectory)) {
            if (Files.notExists(dataDirectory)) {
                throw new IllegalArgumentException("data directory does not exist: " + dataDirectory);
            }
            throw new IllegalArgumentException("data directory is not a readable directory: " + dataDirectory);
        }
        Path projectData = dataDirectory.resolve(DATA_STORE).resolve(PROJECT_DATA);
        if (Files.notExists(projectData)) {
            return List.of();
        }
        if (!Files.isDirectory(projectData)) {
            throw new IllegalArgumentException("project data is not a readable directory: " + projectData);
        }
        try (Stream<Path> children = Files.list(projectData)) {
            List<LegacyProjectLocation> projects = new ArrayList<>();
            for (Path child : children.toList()) {
                if (isProjectDirectory(child)) {
                    projects.add(new LegacyProjectLocation(child.getFileName().toString(), changeLog(child)));
                }
            }
            projects.sort(Comparator.comparing(LegacyProjectLocation::projectId));
            return List.copyOf(projects);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot list project data: " + projectData, exception);
        }
    }

    private static boolean isProjectDirectory(Path child) {
        if (Files.isDirectory(child)) {
            return true;
        }
        if (Files.isRegularFile(child) || Files.notExists(child)) {
            return false;
        }
        throw new IllegalArgumentException("cannot read project entry: " + child);
    }

    public static String projectIdOf(Path changeDataBinary) {
        Objects.requireNonNull(changeDataBinary, "changeDataBinary");
        Path fileName = changeDataBinary.getFileName();
        Path changeData = changeDataBinary.getParent();
        if (fileName == null
                || changeData == null
                || !CHANGE_DATA_FILE.equals(fileName.toString())
                || changeData.getFileName() == null
                || !CHANGE_DATA_DIRECTORY.equals(changeData.getFileName().toString())) {
            throw new IllegalArgumentException("not a change-data.binary path: " + changeDataBinary);
        }
        Path projectDirectory = changeData.getParent();
        if (projectDirectory == null || projectDirectory.getFileName() == null) {
            throw new IllegalArgumentException("change log has no project directory: " + changeDataBinary);
        }
        return projectDirectory.getFileName().toString();
    }

    private static Path changeLog(Path projectDirectory) {
        return projectDirectory.resolve(CHANGE_DATA_DIRECTORY).resolve(CHANGE_DATA_FILE);
    }
}
