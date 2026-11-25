package wprebuild.app.access;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.UserId;

/** PostgreSQL {@code user_identities}: one row per (issuer, subject). */
@Repository
public class JdbcUserIdentityRepository {

    private final JdbcClient jdbc;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserIdentityRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    Optional<UserId> findUserId(String issuer, String subject) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        return jdbc.sql("""
                        select user_id from user_identities
                        where issuer = ? and subject = ?
                        """)
                .param(issuer)
                .param(subject)
                .query((rs, row) -> new UserId(rs.getString("user_id")))
                .optional();
    }

    /**
     * Inserts a binding. On a primary-key conflict (concurrent first login), re-reads and returns the
     * existing user id.
     */
    Optional<UserId> insert(String issuer, String subject, UserId userId) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(userId, "user id");
        int inserted = jdbcTemplate.update("""
                insert into user_identities (issuer, subject, user_id, last_login_at)
                values (?, ?, ?, ?)
                on conflict (issuer, subject) do nothing
                """, issuer, subject, userId.value(), Timestamp.from(Instant.now()));
        if (inserted == 1) {
            return Optional.of(userId);
        }
        return findUserId(issuer, subject);
    }

    void touchLastLogin(String issuer, String subject) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        jdbc.sql("""
                        update user_identities
                        set last_login_at = ?
                        where issuer = ? and subject = ?
                        """)
                .param(Timestamp.from(Instant.now()))
                .param(issuer)
                .param(subject)
                .update();
    }
}
