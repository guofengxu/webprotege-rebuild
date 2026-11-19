package wprebuild.instances;

import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Applies the instance-store Flyway migrations (separate from the editor DB). */
public final class InstanceSchema {

    private InstanceSchema() {}

    public static void migrate(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/instances")
                .load()
                .migrate();
    }
}
