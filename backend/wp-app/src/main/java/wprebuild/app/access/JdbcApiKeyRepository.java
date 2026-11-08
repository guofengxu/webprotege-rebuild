package wprebuild.app.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.UserId;

/** {@code api_keys}. Rows store {@code key_sha256} and never the plaintext. */
@Repository
class JdbcApiKeyRepository {

    private final JdbcClient jdbc;

    JdbcApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    Instant insert(UUID keyId, UserId userId, String name, byte[] digest) {
        return jdbc.sql("""
                        insert into api_keys (key_id, user_id, name, key_sha256)
                        values (?, ?, ?, ?)
                        returning created_at
                        """)
                .param(keyId)
                .param(userId.value())
                .param(name)
                .param(digest)
                .query((rs, row) -> instant(rs, "created_at"))
                .single();
    }

    /** Marks the key used and returns its owner. An unknown digest updates nothing. */
    Optional<UserId> authenticate(byte[] digest) {
        return jdbc.sql("""
                        update api_keys
                        set last_used_at = now()
                        where key_sha256 = ?
                        returning user_id
                        """)
                .param(digest)
                .query((rs, row) -> new UserId(rs.getString("user_id")))
                .optional();
    }

    List<ApiKeyInfo> list(UserId userId) {
        return List.copyOf(jdbc.sql("""
                        select key_id, name, created_at, last_used_at
                        from api_keys
                        where user_id = ?
                        order by created_at desc, key_id
                        """)
                .param(userId.value())
                .query((rs, row) -> new ApiKeyInfo(
                        rs.getObject("key_id", UUID.class),
                        rs.getString("name"),
                        instant(rs, "created_at"),
                        instant(rs, "last_used_at")))
                .list());
    }

    int delete(UserId userId, UUID keyId) {
        return jdbc.sql("delete from api_keys where key_id = ? and user_id = ?")
                .param(keyId)
                .param(userId.value())
                .update();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
