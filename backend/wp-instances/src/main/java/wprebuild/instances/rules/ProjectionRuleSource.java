package wprebuild.instances.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wprebuild.instances.rules.PropertyRange.RangeKind;

/**
 * Loads {@link ValidationRules} from the editor's ontology projection tables using a read-only
 * {@code REPEATABLE READ} snapshot. Callers should use a projection-reader datasource.
 */
public final class ProjectionRuleSource {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRuleSource.class);

    private final DataSource dataSource;

    public ProjectionRuleSource(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ValidationRules load(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            boolean previousReadOnly = connection.isReadOnly();
            int previousIsolation = connection.getTransactionIsolation();
            connection.setAutoCommit(false);
            try {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                long revNo = readProjectState(connection, projectId);
                Set<String> classes = new HashSet<>();
                Set<String> deprecatedClasses = new HashSet<>();
                Set<String> objectProperties = new HashSet<>();
                Set<String> dataProperties = new HashSet<>();
                readEntities(connection, projectId, classes, deprecatedClasses, objectProperties, dataProperties);
                Map<String, Set<String>> parentsByChild = readHierarchy(connection, projectId);
                Map<String, Set<String>> domains = readDomains(connection, projectId);
                Map<String, List<PropertyRange>> ranges = readRanges(connection, projectId);
                connection.commit();
                return new ValidationRules(
                        revNo,
                        classes,
                        deprecatedClasses,
                        objectProperties,
                        dataProperties,
                        parentsByChild,
                        domains,
                        ranges);
            } catch (RuntimeException | SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("failed to load projection rules for " + projectId, e);
            } finally {
                try {
                    connection.setTransactionIsolation(previousIsolation);
                    connection.setReadOnly(previousReadOnly);
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Prefer the original load failure over pool-restore noise.
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not open projection connection", e);
        }
    }

    static IllegalStateException missingProjection(UUID projectId) {
        return new IllegalStateException(
                "project " + projectId + " has no ontology projection; enable projection first");
    }

    private static long readProjectState(Connection connection, UUID projectId) throws SQLException {
        String sql = "select rev_no, contract_version from projection.project_state where project_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw missingProjection(projectId);
                }
                int contractVersion = rs.getInt("contract_version");
                if (contractVersion != ProjectionContract.EXPECTED_VERSION) {
                    log.error(
                            "projection contract_version mismatch for project {}: expected {}, actual {}",
                            projectId,
                            ProjectionContract.EXPECTED_VERSION,
                            contractVersion);
                    throw ProjectionContract.contractMismatch(projectId, contractVersion);
                }
                return rs.getLong("rev_no");
            }
        }
    }

    private static void readEntities(
            Connection connection,
            UUID projectId,
            Set<String> classes,
            Set<String> deprecatedClasses,
            Set<String> objectProperties,
            Set<String> dataProperties)
            throws SQLException {
        String sql = """
                select iri, entity_type, deprecated
                from projection.entities
                where project_id = ?
                  and entity_type in ('CLASS', 'OBJECT_PROPERTY', 'DATA_PROPERTY')
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String iri = rs.getString("iri");
                    String type = rs.getString("entity_type");
                    boolean deprecated = rs.getBoolean("deprecated");
                    switch (type) {
                        case "CLASS" -> {
                            classes.add(iri);
                            if (deprecated) {
                                deprecatedClasses.add(iri);
                            }
                        }
                        case "OBJECT_PROPERTY" -> objectProperties.add(iri);
                        case "DATA_PROPERTY" -> dataProperties.add(iri);
                        default -> {
                            // filtered in SQL
                        }
                    }
                }
            }
        }
    }

    private static Map<String, Set<String>> readHierarchy(Connection connection, UUID projectId) throws SQLException {
        Map<String, Set<String>> parentsByChild = new HashMap<>();
        String sql = """
                select child_iri, parent_iri
                from projection.hierarchy_edges
                where project_id = ? and hierarchy = 'CLASS'
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parentsByChild
                            .computeIfAbsent(rs.getString("child_iri"), k -> new HashSet<>())
                            .add(rs.getString("parent_iri"));
                }
            }
        }
        return parentsByChild;
    }

    private static Map<String, Set<String>> readDomains(Connection connection, UUID projectId) throws SQLException {
        Map<String, Set<String>> domains = new HashMap<>();
        String sql = """
                select property_iri, class_iri
                from projection.property_domains
                where project_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    domains.computeIfAbsent(rs.getString("property_iri"), k -> new HashSet<>())
                            .add(rs.getString("class_iri"));
                }
            }
        }
        return domains;
    }

    private static Map<String, List<PropertyRange>> readRanges(Connection connection, UUID projectId)
            throws SQLException {
        Map<String, List<PropertyRange>> ranges = new HashMap<>();
        String sql = """
                select property_iri, range_iri, range_kind
                from projection.property_ranges
                where project_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RangeKind kind = RangeKind.valueOf(rs.getString("range_kind"));
                    ranges.computeIfAbsent(rs.getString("property_iri"), k -> new ArrayList<>())
                            .add(new PropertyRange(rs.getString("range_iri"), kind));
                }
            }
        }
        return ranges;
    }
}
