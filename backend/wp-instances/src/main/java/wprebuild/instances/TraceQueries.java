package wprebuild.instances;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Caps tips bound into one {@code = any(?)} parameter; batches are merged before expansion. */
    static final int MAX_BATCH = 10_000;

    private final DataSource dataSource;

    public TraceQueries(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Split {@code items} into contiguous chunks of at most {@code size}. */
    static <T> List<List<T>> chunks(List<T> items, int size) {
        Objects.requireNonNull(items, "items");
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        if (items.isEmpty()) {
            return List.of();
        }
        List<List<T>> out = new ArrayList<>((items.size() + size - 1) / size);
        for (int i = 0; i < items.size(); i += size) {
            out.add(List.copyOf(items.subList(i, Math.min(i + size, items.size()))));
        }
        return List.copyOf(out);
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

        try (Connection connection = dataSource.getConnection()) {
            List<List<String>> frontier = List.of(List.of(startIri));
            Timestamp asOfTs = Timestamp.from(asOf);
            for (PathStep step : path) {
                List<String> tips = uniqueTips(frontier);
                Map<String, List<String>> edges = hopEdges(connection, tips, step, asOfTs);
                if (edges.isEmpty()) {
                    return List.of();
                }
                List<List<String>> next = new ArrayList<>();
                for (List<String> current : frontier) {
                    String tip = current.get(current.size() - 1);
                    List<String> neighbors = edges.get(tip);
                    if (neighbors == null) {
                        continue;
                    }
                    for (String neighbor : neighbors) {
                        List<String> extended = new ArrayList<>(current);
                        extended.add(neighbor);
                        next.add(List.copyOf(extended));
                    }
                }
                if (next.isEmpty()) {
                    return List.of();
                }
                frontier = next;
            }
            return List.copyOf(frontier);
        } catch (SQLException e) {
            throw new IllegalStateException("follow failed for " + startIri, e);
        }
    }

    private static List<String> uniqueTips(List<List<String>> frontier) {
        LinkedHashSet<String> tips = new LinkedHashSet<>(frontier.size());
        for (List<String> path : frontier) {
            tips.add(path.get(path.size() - 1));
        }
        return List.copyOf(tips);
    }

    private static Map<String, List<String>> hopEdges(
            Connection connection, List<String> tips, PathStep step, Timestamp asOfTs) throws SQLException {
        String sql = step.direction() == Direction.FORWARD ? """
                        select subject_iri as from_iri, object_iri as to_iri
                        from links
                        where subject_iri = any (?) and property_iri = ?
                          and valid_from <= ? and (valid_to is null or valid_to > ?)
                        """ : """
                        select object_iri as from_iri, subject_iri as to_iri
                        from links
                        where object_iri = any (?) and property_iri = ?
                          and valid_from <= ? and (valid_to is null or valid_to > ?)
                        """;

        Map<String, List<String>> edges = new HashMap<>();
        for (List<String> batch : chunks(tips, MAX_BATCH)) {
            Array tipArray = connection.createArrayOf("text", batch.toArray());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setArray(1, tipArray);
                ps.setString(2, step.propertyIri());
                ps.setTimestamp(3, asOfTs);
                ps.setTimestamp(4, asOfTs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String from = rs.getString("from_iri");
                        String to = rs.getString("to_iri");
                        edges.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
                    }
                }
            } finally {
                freeQuietly(tipArray);
            }
        }
        return edges;
    }

    private static void freeQuietly(Array array) {
        try {
            array.free();
        } catch (SQLException ignored) {
            // Prefer the query failure if both free and execute throw.
        }
    }
}
