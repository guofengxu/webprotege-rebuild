package wprebuild.app.revision;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.codec.OfnLineChangeCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.ConcurrentAppendException;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

/**
 * PostgreSQL {@link RevisionLog}. {@link #append} commits the revision, its subject index, and the
 * outbox row in one transaction (ADR-004, WP-3, WP-5).
 *
 * <p>Not {@code final}: the transaction boundary is a Spring proxy, which subclasses this class.
 */
@Component
public class JdbcRevisionLog implements RevisionLog {

    /**
     * PostgreSQL loads the whole result when autocommit is on. A positive fetch size plus an open
     * transaction makes the driver use a server-side cursor, so {@link #read} does not pull every
     * payload into memory at once. The cursor uses its own connection, so it outlives the caller's
     * Spring transaction.
     */
    private static final int READ_FETCH_SIZE = 64;

    /** Three binds per row, kept under PostgreSQL's 65535-parameter limit. */
    private static final int SUBJECT_BATCH_SIZE = 1000;

    private static final String READ_SQL = """
            select rev_no, user_id, ts, description, codec, payload
            from revisions
            where project_id = ? and rev_no >= ? and rev_no <= ?
            order by rev_no
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final OfnLineChangeCodec codec;
    private final RevisionCommitOutbox outbox;

    public JdbcRevisionLog(
            JdbcClient jdbc, JdbcTemplate jdbcTemplate, OfnLineChangeCodec codec, RevisionCommitOutbox outbox) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public RevisionNumber head(ProjectId projectId) {
        long head = jdbc.sql("select coalesce(max(rev_no), 0) as head from revisions where project_id = ?")
                .param(requireProject(projectId).value())
                .query((rs, rowNum) -> rs.getLong("head"))
                .single();
        return RevisionNumber.of(head);
    }

    @Override
    @Transactional
    public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
        Objects.requireNonNull(expectedHead, "expected head");
        Objects.requireNonNull(revision, "revision");
        RevisionNumber required = expectedHead.next();
        if (!revision.number().equals(required)) {
            throw new IllegalArgumentException("revision number must be %s, not %s"
                    .formatted(required.value(), revision.number().value()));
        }
        ProjectId id = requireProject(projectId);
        long actualHead = head(id).value();
        if (actualHead != expectedHead.value()) {
            throw new ConcurrentAppendException(id, expectedHead, RevisionNumber.of(actualHead));
        }
        byte[] payload = codec.encode(revision.changes());
        Object savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
        try {
            insertRevision(id, revision, payload);
            TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);
        } catch (DuplicateKeyException ex) {
            // PostgreSQL rejects FOR UPDATE on max(). Roll back to the savepoint so the loser's
            // transaction can read the head the winner committed (WP-5).
            TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
            throw new ConcurrentAppendException(id, expectedHead, head(id));
        }
        insertSubjects(id, revision);
        outbox.record(id, revision);
    }

    @Override
    public Optional<Revision> get(ProjectId projectId, RevisionNumber number) {
        Objects.requireNonNull(number, "revision number");
        return jdbc.sql("""
                        select rev_no, user_id, ts, description, codec, payload
                        from revisions
                        where project_id = ? and rev_no = ?
                        """)
                .param(requireProject(projectId).value())
                .param(number.value())
                .query((rs, rowNum) -> mapRevision(rs))
                .optional();
    }

    @Override
    public Stream<Revision> read(ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive) {
        Objects.requireNonNull(fromInclusive, "from");
        Objects.requireNonNull(toInclusive, "to");
        ProjectId id = requireProject(projectId);
        if (fromInclusive.compareTo(toInclusive) > 0) {
            return Stream.empty();
        }
        return streamRevisions(id, fromInclusive, toInclusive);
    }

    private void insertRevision(ProjectId projectId, Revision revision, byte[] payload) {
        jdbc.sql("""
                        insert into revisions (
                            project_id, rev_no, user_id, ts, description, change_count, codec, payload
                        ) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(projectId.value())
                .param(revision.number().value())
                .param(revision.user().value())
                .param(storedTimestamp(revision).atOffset(ZoneOffset.UTC))
                .param(revision.description())
                .param(revision.changes().size())
                .param(codec.id())
                .param(payload)
                .update();
    }

    private void insertSubjects(ProjectId projectId, Revision revision) {
        List<String> subjects = subjectIris(revision.changes());
        if (subjects.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "insert into revision_subjects (project_id, rev_no, subject_iri) values (?, ?, ?)",
                subjects,
                SUBJECT_BATCH_SIZE,
                (statement, iri) -> {
                    statement.setObject(1, projectId.value());
                    statement.setLong(2, revision.number().value());
                    statement.setString(3, iri);
                });
    }

    private Stream<Revision> streamRevisions(ProjectId projectId, RevisionNumber from, RevisionNumber to) {
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "dataSource");
        Connection connection = openReadConnection(dataSource);
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(READ_SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(READ_FETCH_SIZE);
            statement.setObject(1, projectId.value());
            statement.setLong(2, from.value());
            statement.setLong(3, to.value());
            ResultSet resultSet = statement.executeQuery();
            return revisionStream(resultSet, statement, connection);
        } catch (SQLException ex) {
            RuntimeException translated = translated("read revisions", ex);
            closeRead(null, statement, connection, translated);
            throw translated;
        } catch (RuntimeException ex) {
            closeRead(null, statement, connection, ex);
            throw ex;
        }
    }

    /** A pool connection that is not enlisted in the caller's Spring transaction. */
    private Connection openReadConnection(DataSource dataSource) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException ex) {
            JdbcUtils.closeConnection(connection);
            throw translated("read revisions", ex);
        }
    }

    private Stream<Revision> revisionStream(ResultSet resultSet, PreparedStatement statement, Connection connection) {
        Spliterator<Revision> rows = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super Revision> action) {
                try {
                    if (!resultSet.next()) {
                        return false;
                    }
                    action.accept(mapRevision(resultSet));
                    return true;
                } catch (SQLException ex) {
                    throw translated("read revisions", ex);
                }
            }
        };
        return StreamSupport.stream(rows, false).onClose(() -> closeRead(resultSet, statement, connection, null));
    }

    private void closeRead(ResultSet resultSet, Statement statement, Connection connection, RuntimeException failure) {
        JdbcUtils.closeResultSet(resultSet);
        JdbcUtils.closeStatement(statement);
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException ex) {
            RuntimeException translated = translated("close revision stream", ex);
            if (failure != null) {
                failure.addSuppressed(translated);
            } else {
                throw translated;
            }
        } finally {
            JdbcUtils.closeConnection(connection);
        }
    }

    /** {@code timestamptz} stores microseconds. The outbox string uses the same instant. */
    static Instant storedTimestamp(Revision revision) {
        return revision.timestamp().truncatedTo(ChronoUnit.MICROS);
    }

    private Revision mapRevision(ResultSet rs) throws SQLException {
        String storedCodec = rs.getString("codec");
        if (!codec.id().equals(storedCodec)) {
            throw new IllegalArgumentException("unsupported revision codec: " + storedCodec);
        }
        OffsetDateTime timestamp = Objects.requireNonNull(rs.getObject("ts", OffsetDateTime.class), "timestamp");
        return new Revision(
                RevisionNumber.of(rs.getLong("rev_no")),
                new UserId(rs.getString("user_id")),
                timestamp.toInstant(),
                rs.getString("description"),
                codec.decode(rs.getBytes("payload")));
    }

    private RuntimeException translated(String task, SQLException ex) {
        SQLExceptionTranslator translator = jdbcTemplate.getExceptionTranslator();
        if (translator != null) {
            DataAccessException translated = translator.translate(task, READ_SQL, ex);
            if (translated != null) {
                return translated;
            }
        }
        return new UncategorizedSQLException(task, READ_SQL, ex);
    }

    private static List<String> subjectIris(List<OntologyChange> changes) {
        Set<String> iris = new LinkedHashSet<>();
        for (OntologyChange change : changes) {
            for (OWLEntity entity : change.signature()) {
                iris.add(entity.getIRI().toString());
            }
            Optional<OWLAxiom> axiom = change.axiom();
            if (axiom.isPresent()
                    && axiom.get() instanceof OWLAnnotationAssertionAxiom assertion
                    && assertion.getSubject() instanceof IRI iri) {
                iris.add(iri.toString());
            }
        }
        return List.copyOf(iris);
    }

    private static ProjectId requireProject(ProjectId projectId) {
        return Objects.requireNonNull(projectId, "project id");
    }
}
