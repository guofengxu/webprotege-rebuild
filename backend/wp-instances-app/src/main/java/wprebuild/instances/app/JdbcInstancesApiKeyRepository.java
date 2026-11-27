package wprebuild.instances.app;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code api_keys} in the instance-store database. Rows store {@code key_hash}, never plaintext. */
@Repository
class JdbcInstancesApiKeyRepository {

    private final JdbcTemplate jdbc;

    JdbcInstancesApiKeyRepository(@Qualifier(InstancesDataSourceConfig.INSTANCES_DATA_SOURCE) DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * Inserts one row only when no active key exists (atomic vs concurrent bootstrap).
     *
     * @return {@code 1} if inserted, {@code 0} if an active key already existed
     */
    int insertIfNoActive(UUID id, String name, String keyHash, Instant createdAt) {
        return jdbc.update("""
                insert into api_keys (id, name, key_hash, created_at, revoked_at)
                select ?, ?, ?, ?, null
                where not exists (select 1 from api_keys where revoked_at is null)
                """, id, name, keyHash, Timestamp.from(createdAt));
    }

    /** Empty when the hash is unknown or the key has been revoked. */
    Optional<UUID> findActiveIdByHash(String keyHash) {
        return jdbc.query("""
                        select id from api_keys
                        where key_hash = ? and revoked_at is null
                        """, (rs, rowNum) -> (UUID) rs.getObject("id"), keyHash).stream()
                .findFirst();
    }

    /** Empty when the id is unknown or the key has been revoked. */
    Optional<String> findActiveNameById(UUID id) {
        return jdbc.query("""
                        select name from api_keys
                        where id = ? and revoked_at is null
                        """, (rs, rowNum) -> rs.getString("name"), id).stream()
                .findFirst();
    }

    /** {@code 0} when the id is unknown or already revoked. */
    int revoke(UUID id, Instant revokedAt) {
        return jdbc.update("""
                update api_keys
                set revoked_at = ?
                where id = ? and revoked_at is null
                """, Timestamp.from(revokedAt), id);
    }
}
