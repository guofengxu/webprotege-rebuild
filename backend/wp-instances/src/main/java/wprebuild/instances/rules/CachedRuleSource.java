package wprebuild.instances.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-project cache of {@link ValidationRules} that reloads only when {@code
 * projection.project_state.rev_no} advances. Checks {@code contract_version} on every {@link
 * #rulesFor} call and refuses to serve rules (including a stale cache entry) when it does not match
 * {@link ProjectionContract#EXPECTED_VERSION}.
 */
public final class CachedRuleSource {

    private static final Logger log = LoggerFactory.getLogger(CachedRuleSource.class);

    private final DataSource dataSource;
    private final Function<UUID, ValidationRules> loader;
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachedRuleSource(DataSource dataSource) {
        this(dataSource, new ProjectionRuleSource(Objects.requireNonNull(dataSource, "dataSource"))::load);
    }

    /**
     * Package-visible for tests that wrap the full load to count reloads without a background
     * poller.
     */
    CachedRuleSource(DataSource dataSource, Function<UUID, ValidationRules> loader) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public ValidationRules rulesFor(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        ProjectState state = readProjectState(projectId);
        if (state.contractVersion() != ProjectionContract.EXPECTED_VERSION) {
            log.error(
                    "projection contract_version mismatch for project {}: expected {}, actual {}",
                    projectId,
                    ProjectionContract.EXPECTED_VERSION,
                    state.contractVersion());
            throw ProjectionContract.contractMismatch(projectId, state.contractVersion());
        }
        CacheEntry cached = cache.get(projectId);
        if (cached != null && cached.revNo() == state.revNo()) {
            return cached.rules();
        }
        ValidationRules rules = loader.apply(projectId);
        // Key by the loaded ontologyRev so a poll/load skew cannot pin an older cache key.
        cache.put(projectId, new CacheEntry(rules.ontologyRev(), rules));
        return rules;
    }

    private ProjectState readProjectState(UUID projectId) {
        String sql = "select rev_no, contract_version from projection.project_state where project_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ProjectionRuleSource.missingProjection(projectId);
                }
                return new ProjectState(rs.getLong("rev_no"), rs.getInt("contract_version"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read projection project_state for " + projectId, e);
        }
    }

    private record ProjectState(long revNo, int contractVersion) {}

    private record CacheEntry(long revNo, ValidationRules rules) {}
}
