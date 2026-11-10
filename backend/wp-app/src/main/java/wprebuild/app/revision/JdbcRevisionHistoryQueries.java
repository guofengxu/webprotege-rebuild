package wprebuild.app.revision;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.kernel.id.ProjectId;

/**
 * Metadata pages over {@code revisions} and {@code revision_subjects}.
 *
 * <p>The selected columns are {@code rev_no}, {@code user_id}, {@code ts}, {@code description}, and
 * {@code change_count}. {@code payload} is never read. Entity history filters the subject index
 * {@code (project_id, subject_iri, rev_no)} instead of scanning every revision (P3-20).
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Repository
@Transactional(readOnly = true)
public class JdbcRevisionHistoryQueries implements RevisionHistoryQueries {

    /**
     * Count and page share one statement, so a commit between them cannot make the page larger than
     * {@code total} under READ COMMITTED.
     */
    private static final String PROJECT_PAGE = """
            select page.rev_no, page.user_id, page.ts, page.description, page.change_count, counted.total
            from (select count(*) as total from revisions where project_id = ?) counted
            left join lateral (
                select rev_no, user_id, ts, description, change_count
                from revisions
                where project_id = ?
                order by rev_no desc
                limit ? offset ?
            ) page on true
            """;

    private static final String SUBJECT_PAGE = """
            select page.rev_no, page.user_id, page.ts, page.description, page.change_count, counted.total
            from (
                select count(*) as total
                from revision_subjects
                where project_id = ? and subject_iri = ?
            ) counted
            left join lateral (
                select r.rev_no, r.user_id, r.ts, r.description, r.change_count
                from revision_subjects s
                join revisions r on r.project_id = s.project_id and r.rev_no = s.rev_no
                where s.project_id = ? and s.subject_iri = ?
                order by s.rev_no desc
                limit ? offset ?
            ) page on true
            """;

    private final JdbcClient jdbc;

    public JdbcRevisionHistoryQueries(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public RevisionPage list(ProjectId projectId, int page, int size) {
        check(page, size);
        UUID id = requireProject(projectId).value();
        long start = offset(page, size);
        List<RawRow> rows = jdbc.sql(PROJECT_PAGE)
                .param(id)
                .param(id)
                .param(size)
                .param(start)
                .query(this::map)
                .list();
        return assemble(rows, page, size);
    }

    @Override
    public RevisionPage listBySubject(ProjectId projectId, IRI subject, int page, int size) {
        check(page, size);
        UUID id = requireProject(projectId).value();
        String iri = requireSubject(subject).toString();
        long start = offset(page, size);
        List<RawRow> rows = jdbc.sql(SUBJECT_PAGE)
                .param(id)
                .param(iri)
                .param(id)
                .param(iri)
                .param(size)
                .param(start)
                .query(this::map)
                .list();
        return assemble(rows, page, size);
    }

    private static RevisionPage assemble(List<RawRow> rows, int page, int size) {
        if (rows.isEmpty()) {
            return new RevisionPage(List.of(), page, size, 0);
        }
        List<RevisionSummary> summaries = new ArrayList<>();
        for (RawRow row : rows) {
            if (row.summary() != null) {
                summaries.add(row.summary());
            }
        }
        return new RevisionPage(summaries, page, size, rows.getFirst().total());
    }

    private RawRow map(ResultSet rs, int row) throws SQLException {
        long revNo = rs.getLong("rev_no");
        boolean missing = rs.wasNull();
        long total = rs.getLong("total");
        if (missing) {
            return new RawRow(null, total);
        }
        OffsetDateTime timestamp = Objects.requireNonNull(rs.getObject("ts", OffsetDateTime.class), "timestamp");
        RevisionSummary summary = new RevisionSummary(
                revNo,
                rs.getString("user_id"),
                timestamp.toInstant(),
                rs.getString("description"),
                rs.getInt("change_count"));
        return new RawRow(summary, total);
    }

    private static void check(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be from 1 to " + MAX_SIZE);
        }
    }

    private static long offset(int page, int size) {
        return (long) page * size;
    }

    private static ProjectId requireProject(ProjectId projectId) {
        return Objects.requireNonNull(projectId, "project id");
    }

    private static IRI requireSubject(IRI subject) {
        Objects.requireNonNull(subject, "subject");
        if (!subject.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        return subject;
    }

    private record RawRow(RevisionSummary summary, long total) {}
}
