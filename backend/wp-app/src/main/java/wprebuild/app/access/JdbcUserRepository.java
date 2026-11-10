package wprebuild.app.access;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.UserId;

/** PostgreSQL {@code users} table. Writes are package-private so passwords are hashed in {@link UserService}. */
@Repository
public class JdbcUserRepository {

    private final JdbcClient jdbc;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public Optional<UserAccount> findById(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        return jdbc.sql("""
                        select user_id, display_name, email, password_hash, app_roles
                        from users
                        where user_id = ?
                        """).param(userId.value()).query((rs, row) -> map(rs)).optional();
    }

    /** Role names only. Permission checks must not load {@code password_hash}. */
    List<String> appRoles(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        return jdbc.sql("select app_roles from users where user_id = ?")
                .param(userId.value())
                .query((rs, row) -> readRoles(rs))
                .optional()
                .orElse(List.of());
    }

    boolean isEmpty() {
        return jdbc.sql("select not exists (select 1 from users) as empty")
                .query((rs, row) -> rs.getBoolean("empty"))
                .single();
    }

    /**
     * Serializes the empty-table check with the insert. PostgreSQL rejects {@code LOCK TABLE} outside a
     * transaction, so callers run this inside one.
     */
    void lockUsers() {
        jdbcTemplate.execute("lock table users in exclusive mode");
    }

    int insert(UserAccount account) {
        Objects.requireNonNull(account, "account");
        return jdbcTemplate.update("""
                insert into users (user_id, display_name, email, password_hash, app_roles)
                values (?, ?, ?, ?, ?)
                on conflict (user_id) do nothing
                """, ps -> bind(ps, account));
    }

    int updatePasswordHash(UserId userId, String passwordHash) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(passwordHash, "password hash");
        return jdbc.sql("update users set password_hash = ? where user_id = ?")
                .param(passwordHash)
                .param(userId.value())
                .update();
    }

    /** One consistent count and page. An empty page still returns {@code total}. */
    UserSlice slice(int limit, long offset) {
        return jdbcTemplate.query(
                """
                select page.user_id, page.display_name, page.email, page.password_hash, page.app_roles,
                       counted.total
                from (select count(*) as total from users) counted
                left join lateral (
                    select user_id, display_name, email, password_hash, app_roles
                    from users
                    order by user_id
                    limit ? offset ?
                ) page on true
                """,
                ps -> {
                    ps.setInt(1, limit);
                    ps.setLong(2, offset);
                },
                this::readSlice);
    }

    int countWithRole(String role) {
        Objects.requireNonNull(role, "role");
        return jdbc.sql("select count(*) from users where ? = any (app_roles)")
                .param(role)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    int updateAppRoles(UserId userId, List<String> appRoles) {
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(appRoles, "app roles");
        return jdbcTemplate.update("update users set app_roles = ? where user_id = ?", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", appRoles.toArray(String[]::new)));
            ps.setString(2, userId.value());
        });
    }

    private static void bind(PreparedStatement ps, UserAccount account) throws SQLException {
        ps.setString(1, account.userId().value());
        ps.setString(2, account.displayName());
        ps.setString(3, account.email());
        ps.setString(4, account.passwordHash());
        ps.setArray(
                5, ps.getConnection().createArrayOf("text", account.appRoles().toArray(String[]::new)));
    }

    private static UserAccount map(ResultSet rs) throws SQLException {
        return new UserAccount(
                new UserId(rs.getString("user_id")),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                readRoles(rs));
    }

    private static UserProfile profile(ResultSet rs) throws SQLException {
        String passwordHash = rs.getString("password_hash");
        return new UserProfile(
                new UserId(rs.getString("user_id")),
                rs.getString("display_name"),
                rs.getString("email"),
                passwordHash == null || passwordHash.isBlank(),
                readRoles(rs));
    }

    private UserSlice readSlice(ResultSet rs) throws SQLException {
        List<UserProfile> users = new ArrayList<>();
        long total = 0;
        while (rs.next()) {
            total = rs.getLong("total");
            if (rs.getString("user_id") != null) {
                users.add(profile(rs));
            }
        }
        return new UserSlice(List.copyOf(users), total);
    }

    record UserSlice(List<UserProfile> users, long total) {}

    private static List<String> readRoles(ResultSet rs) throws SQLException {
        Array roles = rs.getArray("app_roles");
        String[] values = roles == null ? new String[0] : (String[]) roles.getArray();
        return List.of(values);
    }
}
