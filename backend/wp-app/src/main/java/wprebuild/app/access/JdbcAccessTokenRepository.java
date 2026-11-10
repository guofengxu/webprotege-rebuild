package wprebuild.app.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.UserId;

/** {@code access_tokens} and {@code rotated_refresh_tokens}. Rows store SHA-256 digests, never the plaintext. */
@Repository
class JdbcAccessTokenRepository {

    private final JdbcClient jdbc;

    JdbcAccessTokenRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    void insert(
            UUID tokenId,
            UserId userId,
            String deviceName,
            byte[] accessDigest,
            byte[] refreshDigest,
            Instant accessExpiresAt,
            Instant refreshExpiresAt,
            Instant createdAt) {
        jdbc.sql("""
                        insert into access_tokens (
                            token_id, user_id, device_name, access_sha256, refresh_sha256,
                            access_expires_at, refresh_expires_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(tokenId)
                .param(userId.value())
                .param(deviceName)
                .param(accessDigest)
                .param(refreshDigest)
                .param(stored(accessExpiresAt))
                .param(stored(refreshExpiresAt))
                .param(stored(createdAt))
                .update();
    }

    /** Marks the access token used and returns its owner. An unknown, expired, or revoked digest updates nothing. */
    Optional<UserId> authenticate(byte[] accessDigest, Instant now) {
        return jdbc.sql("""
                        update access_tokens
                        set last_used_at = ?
                        where access_sha256 = ?
                          and revoked_at is null
                          and access_expires_at > ?
                        returning user_id
                        """)
                .param(stored(now))
                .param(accessDigest)
                .param(stored(now))
                .query((rs, row) -> new UserId(rs.getString("user_id")))
                .optional();
    }

    /** Owner of a refresh token that is still current. Expired and revoked rows are absent. */
    Optional<UserId> currentOwner(byte[] refreshDigest, Instant now) {
        return jdbc.sql("""
                        select user_id
                        from access_tokens
                        where refresh_sha256 = ?
                          and revoked_at is null
                          and refresh_expires_at > ?
                        """)
                .param(refreshDigest)
                .param(stored(now))
                .query((rs, row) -> new UserId(rs.getString("user_id")))
                .optional();
    }

    /**
     * Replaces both hashes on the row that still holds {@code previousRefreshDigest} and records that hash as
     * rotated. Empty when the token is no longer current.
     */
    Optional<CurrentToken> rotate(
            byte[] previousRefreshDigest,
            byte[] accessDigest,
            byte[] refreshDigest,
            Instant accessExpiresAt,
            Instant refreshExpiresAt,
            Instant now) {
        Optional<CurrentToken> updated = jdbc.sql("""
                        update access_tokens
                        set access_sha256 = ?,
                            refresh_sha256 = ?,
                            access_expires_at = ?,
                            refresh_expires_at = ?,
                            last_used_at = ?
                        where refresh_sha256 = ?
                          and revoked_at is null
                          and refresh_expires_at > ?
                        returning token_id, user_id
                        """)
                .param(accessDigest)
                .param(refreshDigest)
                .param(stored(accessExpiresAt))
                .param(stored(refreshExpiresAt))
                .param(stored(now))
                .param(previousRefreshDigest)
                .param(stored(now))
                .query(this::currentToken)
                .optional();
        updated.ifPresent(token -> rememberRotated(previousRefreshDigest, token.tokenId()));
        return updated;
    }

    Optional<UUID> findRotated(byte[] refreshDigest) {
        return jdbc.sql("select token_id from rotated_refresh_tokens where refresh_sha256 = ?")
                .param(refreshDigest)
                .query((rs, row) -> rs.getObject("token_id", UUID.class))
                .optional();
    }

    void revokeSession(UUID tokenId, Instant now) {
        jdbc.sql("""
                        update access_tokens
                        set revoked_at = ?
                        where token_id = ?
                          and revoked_at is null
                        """).param(stored(now)).param(tokenId).update();
    }

    int revoke(UserId userId, UUID tokenId, Instant now) {
        return jdbc.sql("""
                        update access_tokens
                        set revoked_at = ?
                        where token_id = ?
                          and user_id = ?
                          and revoked_at is null
                        """)
                .param(stored(now))
                .param(tokenId)
                .param(userId.value())
                .update();
    }

    List<DeviceSession> listActive(UserId userId) {
        return List.copyOf(
                jdbc.sql("""
                        select token_id, device_name, created_at, last_used_at,
                               access_expires_at, refresh_expires_at
                        from access_tokens
                        where user_id = ?
                          and revoked_at is null
                        order by created_at desc, token_id
                        """).param(userId.value()).query(this::deviceSession).list());
    }

    private void rememberRotated(byte[] refreshDigest, UUID tokenId) {
        jdbc.sql("""
                        insert into rotated_refresh_tokens (refresh_sha256, token_id)
                        values (?, ?)
                        """).param(refreshDigest).param(tokenId).update();
    }

    private CurrentToken currentToken(ResultSet rs, int row) throws SQLException {
        return new CurrentToken(rs.getObject("token_id", UUID.class), new UserId(rs.getString("user_id")));
    }

    private DeviceSession deviceSession(ResultSet rs, int row) throws SQLException {
        return new DeviceSession(
                rs.getObject("token_id", UUID.class),
                rs.getString("device_name"),
                instant(rs, "created_at"),
                instant(rs, "last_used_at"),
                instant(rs, "access_expires_at"),
                instant(rs, "refresh_expires_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime stored(Instant instant) {
        return Objects.requireNonNull(instant, "timestamp").atOffset(ZoneOffset.UTC);
    }

    record CurrentToken(UUID tokenId, UserId userId) {}
}
