package wprebuild.instances;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Multi-hop relationship queries over {@code links}.
 *
 * <p>Fan-out grows exponentially with depth; callers must pass a finite {@code maxHops}. Cycle
 * detection uses a path array so a node is never revisited within one walk.
 */
public final class TraceQueries {

    private final DataSource dataSource;

    public TraceQueries(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * IRIs reachable from {@code startIri} by following {@code propertyIris} in {@code direction},
     * up to {@code maxHops}, as of {@code asOf}. Complexity is exponential in fan-out × hops.
     */
    public List<String> reachable(
            String startIri, Set<String> propertyIris, Direction direction, int maxHops, Instant asOf) {
        Objects.requireNonNull(startIri, "startIri");
        Objects.requireNonNull(propertyIris, "propertyIris");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(asOf, "asOf");
        if (maxHops < 1) {
            throw new IllegalArgumentException("maxHops must be >= 1");
        }
        if (propertyIris.isEmpty()) {
            return List.of();
        }

        String joinOn = direction == Direction.FORWARD ? "l.subject_iri = w.iri" : "l.object_iri = w.iri";
        String nextIri = direction == Direction.FORWARD ? "l.object_iri" : "l.subject_iri";
        String seedJoin = direction == Direction.FORWARD ? "l.subject_iri = ?" : "l.object_iri = ?";

        String sql = """
                with recursive walk as (
                    select %s as iri,
                           1 as depth,
                           array[?::text, %s]::text[] as path
                    from links l
                    where %s
                      and l.property_iri = any (?)
                      and l.valid_from <= ?
                      and (l.valid_to is null or l.valid_to > ?)
                    union all
                    select %s,
                           w.depth + 1,
                           w.path || %s
                    from walk w
                    join links l on %s
                       and l.property_iri = any (?)
                       and l.valid_from <= ?
                       and (l.valid_to is null or l.valid_to > ?)
                    where w.depth < ?
                      and not (%s = any (w.path))
                )
                select distinct iri from walk
                """.formatted(nextIri, nextIri, seedJoin, nextIri, nextIri, joinOn, nextIri);

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            Timestamp asOfTs = Timestamp.from(asOf);
            Array props = connection.createArrayOf("text", propertyIris.toArray());
            int i = 1;
            ps.setString(i++, startIri);
            ps.setString(i++, startIri);
            ps.setArray(i++, props);
            ps.setTimestamp(i++, asOfTs);
            ps.setTimestamp(i++, asOfTs);
            ps.setArray(i++, props);
            ps.setTimestamp(i++, asOfTs);
            ps.setTimestamp(i++, asOfTs);
            ps.setInt(i, maxHops);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return List.copyOf(out);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reachable failed for " + startIri, e);
        }
    }

    /**
     * Follow a fixed sequence of property steps from {@code startIri}. Each returned list is a full
     * path {@code [start, hop1, hop2, ...]} that matches every step as of {@code asOf}.
     */
    public List<List<String>> follow(String startIri, List<PathStep> path, Instant asOf) {
        Objects.requireNonNull(startIri, "startIri");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(asOf, "asOf");
        if (path.isEmpty()) {
            return List.of(List.of(startIri));
        }

        List<List<String>> frontier = List.of(List.of(startIri));
        for (PathStep step : path) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> current : frontier) {
                String tip = current.get(current.size() - 1);
                for (String neighbor : neighbors(tip, step, asOf)) {
                    List<String> extended = new ArrayList<>(current);
                    extended.add(neighbor);
                    next.add(List.copyOf(extended));
                }
            }
            frontier = next;
            if (frontier.isEmpty()) {
                return List.of();
            }
        }
        return List.copyOf(frontier);
    }

    private List<String> neighbors(String iri, PathStep step, Instant asOf) {
        String sql = step.direction() == Direction.FORWARD ? """
                        select object_iri
                        from links
                        where subject_iri = ? and property_iri = ?
                          and valid_from <= ? and (valid_to is null or valid_to > ?)
                        """ : """
                        select subject_iri
                        from links
                        where object_iri = ? and property_iri = ?
                          and valid_from <= ? and (valid_to is null or valid_to > ?)
                        """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            Timestamp asOfTs = Timestamp.from(asOf);
            ps.setString(1, iri);
            ps.setString(2, step.propertyIri());
            ps.setTimestamp(3, asOfTs);
            ps.setTimestamp(4, asOfTs);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("neighbors failed for " + iri, e);
        }
    }
}
