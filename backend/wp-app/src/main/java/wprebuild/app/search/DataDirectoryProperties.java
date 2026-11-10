package wprebuild.app.search;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import wprebuild.kernel.id.ProjectId;

/** {@code wp.data-dir}. Each project index lives in {@code search/<projectId>} under it. */
@ConfigurationProperties(prefix = "wp")
public record DataDirectoryProperties(@DefaultValue("data") Path dataDir) {

    public DataDirectoryProperties {
        Objects.requireNonNull(dataDir, "wp.data-dir");
        if (dataDir.toString().isBlank()) {
            throw new IllegalArgumentException("wp.data-dir must not be blank");
        }
    }

    public Path indexDirectory(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return dataDir.resolve("search").resolve(projectId.toString());
    }
}
