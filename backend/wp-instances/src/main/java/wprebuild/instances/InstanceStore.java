package wprebuild.instances;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC store for versioned instances and links. All clocks are caller-supplied; this class never
 * calls {@code now()}.
 */
public final class InstanceStore {

    private final DataSource dataSource;

    public InstanceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Insert or version an instance. Same content is a no-op. Class or attribute changes close the
     * current row and insert a new one. Concurrent first inserts that hit the unique current-version
     * index are retried once.
     */
    public void upsert(InstanceRecord record, Instant at) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(at, "at");
        // Unique-violation aborts the Postgres transaction; retry the whole unit once.
        try {
            withTransaction(connection -> {
                doUpsert(connection, record, at);
                return null;
            });
        } catch (UniqueConflictException first) {
            try {
                withTransaction(connection -> {
                    doUpsert(connection, record, at);
                    return null;
                });
            } catch (UniqueConflictException second) {
                throw new IllegalStateException("concurrent write conflict after retry", second);
            }
        }
    }

    /** Close the current instance version and all current links that mention it. */
    public void retire(String iri, Instant at) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(at, "at");
        withTransaction(connection -> {
            doRetire(connection, iri, at);
            return null;
        });
    }

    /**
     * Open a current link if absent. Idempotent when the current triple already exists. Concurrent
     * inserts that hit the unique index are retried once.
     */
    public void link(LinkRecord link, Instant at) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(at, "at");
        try {
            withTransaction(connection -> {
                doLink(connection, link, at);
                return null;
            });
        } catch (UniqueConflictException first) {
            try {
                withTransaction(connection -> {
                    doLink(connection, link, at);
                    return null;
                });
            } catch (UniqueConflictException second) {
                throw new IllegalStateException("concurrent write conflict after retry", second);
            }
        }
    }

    /** Close the current version of a link if present. */
    public void unlink(LinkRecord link, Instant at) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(at, "at");
        withTransaction(connection -> {
            doUnlink(connection, link, at);
            return null;
        });
    }

    /**
     * Apply every mutation in one JDBC transaction. Any failure rolls the whole batch back. Unique
     * conflicts retry the entire batch once (same policy as single-op writers).
     */
    public void applyBatch(List<WriteOp> ops, Instant at) {
        Objects.requireNonNull(ops, "ops");
        Objects.requireNonNull(at, "at");
        List<WriteOp> batch = List.copyOf(ops);
        try {
            withTransaction(connection -> {
                applyAll(connection, batch, at);
                return null;
            });
        } catch (UniqueConflictException first) {
            try {
                withTransaction(connection -> {
                    applyAll(connection, batch, at);
                    return null;
                });
            } catch (UniqueConflictException second) {
                throw new IllegalStateException("concurrent write conflict after retry", second);
            }
        }
    }

    /** Point-in-time lookup using half-open validity {@code [valid_from, valid_to)}. */
    public Optional<InstanceRecord> find(String iri, Instant asOf) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(asOf, "asOf");
        String sql = """
                select iri, class_iri, attributes::text, source, ontology_rev
                from instances
                where iri = ?
                  and valid_from <= ?
                  and (valid_to is null or valid_to > ?)
                order by valid_from desc
                limit 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            Timestamp asOfTs = Timestamp.from(asOf);
            ps.setString(1, iri);
            ps.setTimestamp(2, asOfTs);
            ps.setTimestamp(3, asOfTs);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readInstance(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("find failed for " + iri, e);
        }
    }

    private void applyAll(Connection connection, List<WriteOp> ops, Instant at) throws SQLException {
        for (WriteOp op : ops) {
            switch (op) {
                case WriteOp.Upsert(InstanceRecord record) -> doUpsert(connection, record, at);
                case WriteOp.Retire(String iri) -> doRetire(connection, iri, at);
                case WriteOp.Link(LinkRecord link) -> doLink(connection, link, at);
                case WriteOp.Unlink(LinkRecord link) -> doUnlink(connection, link, at);
            }
        }
    }

    private void doRetire(Connection connection, String iri, Instant at) throws SQLException {
        Optional<LockedInstance> current = lockCurrentInstance(connection, iri);
        if (current.isPresent()) {
            LockedInstance row = current.get();
            requireStrictlyAfter(at, row.validFrom());
            closeInstance(connection, iri, row.validFrom(), at);
        }
        closeLinksMentioning(connection, iri, at);
    }

    private void doUnlink(Connection connection, LinkRecord link, Instant at) throws SQLException {
        Optional<LockedLink> current = lockCurrentLink(connection, link);
        if (current.isPresent()) {
            LockedLink row = current.get();
            requireStrictlyAfter(at, row.validFrom());
            closeLink(connection, link, row.validFrom(), at);
        }
    }

    private void doUpsert(Connection connection, InstanceRecord record, Instant at) throws SQLException {
        Optional<LockedInstance> current = lockCurrentInstance(connection, record.iri());
        if (current.isPresent()) {
            LockedInstance row = current.get();
            if (sameContent(connection, row, record)) {
                rejectRewritingHistory(at, row.validFrom());
                return;
            }
            requireStrictlyAfter(at, row.validFrom());
            closeInstance(connection, record.iri(), row.validFrom(), at);
        }
        insertInstance(connection, record, at);
    }

    private void doLink(Connection connection, LinkRecord link, Instant at) throws SQLException {
        Optional<LockedLink> current = lockCurrentLink(connection, link);
        if (current.isPresent()) {
            rejectRewritingHistory(at, current.get().validFrom());
            return;
        }
        insertLink(connection, link, at);
    }

    private Optional<LockedInstance> lockCurrentInstance(Connection connection, String iri) throws SQLException {
        String sql = """
                select class_iri, attributes::text, source, ontology_rev, valid_from
                from instances
                where iri = ? and valid_to is null
                for update
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, iri);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LockedInstance(
                        rs.getString("class_iri"),
                        rs.getString(2),
                        rs.getString("source"),
                        rs.getLong("ontology_rev"),
                        rs.getTimestamp("valid_from").toInstant()));
            }
        }
    }

    private Optional<LockedLink> lockCurrentLink(Connection connection, LinkRecord link) throws SQLException {
        String sql = """
                select valid_from
                from links
                where subject_iri = ? and property_iri = ? and object_iri = ? and valid_to is null
                for update
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, link.subjectIri());
            ps.setString(2, link.propertyIri());
            ps.setString(3, link.objectIri());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LockedLink(rs.getTimestamp("valid_from").toInstant()));
            }
        }
    }

    private static boolean sameContent(Connection connection, LockedInstance row, InstanceRecord record)
            throws SQLException {
        if (!row.classIri().equals(record.classIri())
                || !row.source().equals(record.source())
                || row.ontologyRev() != record.ontologyRev()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement("select ?::jsonb = ?::jsonb")) {
            ps.setString(1, row.attributesJson());
            ps.setString(2, record.attributesJson());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private void insertInstance(Connection connection, InstanceRecord record, Instant at) throws SQLException {
        String sql = """
                insert into instances (iri, class_iri, attributes, valid_from, valid_to, source, ontology_rev)
                values (?, ?, ?::jsonb, ?, null, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, record.iri());
            ps.setString(2, record.classIri());
            ps.setString(3, record.attributesJson());
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setString(5, record.source());
            ps.setLong(6, record.ontologyRev());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new UniqueConflictException(e);
            }
            throw e;
        }
    }

    private void closeInstance(Connection connection, String iri, Instant validFrom, Instant at) throws SQLException {
        String sql = """
                update instances
                set valid_to = ?
                where iri = ? and valid_from = ? and valid_to is null
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setString(2, iri);
            ps.setTimestamp(3, Timestamp.from(validFrom));
            ps.executeUpdate();
        }
    }

    private void insertLink(Connection connection, LinkRecord link, Instant at) throws SQLException {
        String sql = """
                insert into links (subject_iri, property_iri, object_iri, valid_from, valid_to)
                values (?, ?, ?, ?, null)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, link.subjectIri());
            ps.setString(2, link.propertyIri());
            ps.setString(3, link.objectIri());
            ps.setTimestamp(4, Timestamp.from(at));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new UniqueConflictException(e);
            }
            throw e;
        }
    }

    private void closeLink(Connection connection, LinkRecord link, Instant validFrom, Instant at) throws SQLException {
        String sql = """
                update links
                set valid_to = ?
                where subject_iri = ? and property_iri = ? and object_iri = ?
                  and valid_from = ? and valid_to is null
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setString(2, link.subjectIri());
            ps.setString(3, link.propertyIri());
            ps.setString(4, link.objectIri());
            ps.setTimestamp(5, Timestamp.from(validFrom));
            ps.executeUpdate();
        }
    }

    private void closeLinksMentioning(Connection connection, String iri, Instant at) throws SQLException {
        String lockSql = """
                select subject_iri, property_iri, object_iri, valid_from
                from links
                where valid_to is null and (subject_iri = ? or object_iri = ?)
                order by subject_iri, property_iri, object_iri, valid_from
                for update
                """;
        try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
            lock.setString(1, iri);
            lock.setString(2, iri);
            try (ResultSet rs = lock.executeQuery()) {
                while (rs.next()) {
                    Instant validFrom = rs.getTimestamp("valid_from").toInstant();
                    requireStrictlyAfter(at, validFrom);
                    LinkRecord link = new LinkRecord(
                            rs.getString("subject_iri"), rs.getString("property_iri"), rs.getString("object_iri"));
                    closeLink(connection, link, validFrom, at);
                }
            }
        }
    }

    private static InstanceRecord readInstance(ResultSet rs) throws SQLException {
        return new InstanceRecord(
                rs.getString("iri"),
                rs.getString("class_iri"),
                rs.getString(3),
                rs.getString("source"),
                rs.getLong("ontology_rev"));
    }

    /** Idempotent paths may reuse the creation instant; earlier clocks rewrite history. */
    private static void rejectRewritingHistory(Instant at, Instant validFrom) {
        if (at.isBefore(validFrom)) {
            throw new IllegalArgumentException("at (" + at + ") is before current valid_from (" + validFrom + ")");
        }
    }

    /** Closing a version requires a strictly later instant so valid_to > valid_from. */
    private static void requireStrictlyAfter(Instant at, Instant validFrom) {
        if (!at.isAfter(validFrom)) {
            throw new IllegalArgumentException("at (" + at + ") must be after current valid_from (" + validFrom + ")");
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(e.getSQLState())
                || (e.getNextException() != null
                        && "23505".equals(e.getNextException().getSQLState()));
    }

    private <T> T withTransaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (UniqueConflictException e) {
                connection.rollback();
                throw e;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("transaction failed", e);
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not open connection", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record LockedInstance(
            String classIri, String attributesJson, String source, long ontologyRev, Instant validFrom) {}

    private record LockedLink(Instant validFrom) {}

    private static final class UniqueConflictException extends RuntimeException {
        UniqueConflictException(SQLException cause) {
            super(cause);
        }
    }
}
