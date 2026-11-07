package wprebuild.migrator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

public final class MigratorVersion implements IVersionProvider {

    static final String RESOURCE = "/wprebuild/migrator/version.properties";

    @Override
    public String[] getVersion() throws IOException {
        try (InputStream in = MigratorVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.contains("${")) {
                throw new IllegalStateException("unfiltered version: " + version);
            }
            return new String[] {MigratorInfo.NAME + " " + version};
        }
    }
}
