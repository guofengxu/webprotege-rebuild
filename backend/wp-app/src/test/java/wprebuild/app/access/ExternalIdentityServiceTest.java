package wprebuild.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.UserId;

/** External identity resolution for OIDC / WeCom (P6-37). */
@DatabaseTest
class ExternalIdentityServiceTest {

    private static final ExternalLoginPolicy NO_LINK = new ExternalLoginPolicy(false, false);

    private static final ExternalLoginPolicy LINK_EMAIL = new ExternalLoginPolicy(true, false);

    private static final ExternalLoginPolicy AUTO_PROVISION = new ExternalLoginPolicy(false, true);

    private final ExternalIdentityService identities;

    private final UserService users;

    private final JdbcClient jdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    ExternalIdentityServiceTest(ExternalIdentityService identities, UserService users, JdbcClient jdbc) {
        this.identities = identities;
        this.users = users;
        this.jdbc = jdbc;
    }

    @Test
    void existing_binding_returns_user_and_updates_last_login() {
        String userId = "bound-" + suffix;
        users.create(userId, "Bound", userId + "@example.com", "password-12", List.of());
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-bound-" + suffix;
        insertBinding(issuer, subject, userId, Instant.parse("2020-01-01T00:00:00Z"));

        Optional<UserId> resolved =
                identities.resolve(identity(issuer, subject, userId + "@example.com", true, userId, "Bound"), NO_LINK);

        assertThat(resolved).contains(new UserId(userId));
        Instant lastLogin = lastLoginAt(issuer, subject);
        assertThat(lastLogin).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void link_by_verified_email_binds_the_only_matching_user() {
        String userId = "alice-" + suffix;
        String email = "Alice-" + suffix + "@Example.COM";
        users.create(userId, "Alice", email, "password-12", List.of());
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-alice-" + suffix;

        Optional<UserId> resolved = identities.resolve(
                identity(issuer, subject, "alice-" + suffix + "@example.com", true, "alice", "Alice"), LINK_EMAIL);

        assertThat(resolved).contains(new UserId(userId));
        assertThat(boundUser(issuer, subject)).isEqualTo(userId);
    }

    @Test
    void auto_provision_creates_user_without_password_or_roles() {
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-new-" + suffix;
        String preferred = "NewUser." + suffix;

        Optional<UserId> resolved = identities.resolve(
                identity(issuer, subject, preferred + "@corp.example", true, preferred, "New User"), AUTO_PROVISION);

        String expectedId = ("newuser." + suffix).toLowerCase();
        assertThat(resolved).contains(new UserId(expectedId));
        UserAccount created = users.findById(new UserId(expectedId)).orElseThrow();
        assertThat(created.passwordResetRequired()).isTrue();
        assertThat(created.passwordHash()).isNull();
        assertThat(created.appRoles()).isEmpty();
        assertThat(boundUser(issuer, subject)).isEqualTo(expectedId);
    }

    @Test
    void without_policy_flags_returns_empty_when_unbound() {
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-none-" + suffix;

        Optional<UserId> resolved = identities.resolve(
                identity(issuer, subject, "nobody-" + suffix + "@example.com", true, "nobody", "Nobody"), NO_LINK);

        assertThat(resolved).isEmpty();
        assertThat(bindingCount(issuer, subject)).isZero();
    }

    @Test
    void unverified_email_does_not_link() {
        String userId = "verify-" + suffix;
        String email = "verify-" + suffix + "@example.com";
        users.create(userId, "Verify", email, "password-12", List.of());
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-verify-" + suffix;

        Optional<UserId> resolved =
                identities.resolve(identity(issuer, subject, email, false, userId, "Verify"), LINK_EMAIL);

        assertThat(resolved).isEmpty();
        assertThat(bindingCount(issuer, subject)).isZero();
    }

    @Test
    void two_users_with_same_email_do_not_link() {
        String email = "shared-" + suffix + "@example.com";
        users.create("one-" + suffix, "One", email, "password-12", List.of());
        users.create("two-" + suffix, "Two", email.toUpperCase(), "password-12", List.of());
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-shared-" + suffix;

        Optional<UserId> resolved =
                identities.resolve(identity(issuer, subject, email, true, "shared", "Shared"), LINK_EMAIL);

        assertThat(resolved).isEmpty();
        assertThat(bindingCount(issuer, subject)).isZero();
    }

    @Test
    void system_admin_is_not_linked_by_email() {
        String userId = "root-" + suffix;
        String email = "root-" + suffix + "@example.com";
        users.create(userId, "Root", email, "password-12", List.of("SYSTEM_ADMIN"));
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-root-" + suffix;

        Optional<UserId> resolved =
                identities.resolve(identity(issuer, subject, email, true, userId, "Root"), LINK_EMAIL);

        assertThat(resolved).isEmpty();
        assertThat(bindingCount(issuer, subject)).isZero();
    }

    @Test
    void binding_to_deleted_user_returns_empty() {
        String userId = "gone-" + suffix;
        users.create(userId, "Gone", userId + "@example.com", "password-12", List.of());
        users.create(
                "admin-" + suffix, "Admin", "admin-" + suffix + "@example.com", "password-12", List.of("SYSTEM_ADMIN"));
        users.delete(new UserId(userId), new UserId("admin-" + suffix));
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-gone-" + suffix;
        insertBinding(issuer, subject, userId, Instant.parse("2020-01-01T00:00:00Z"));

        Optional<UserId> resolved = identities.resolve(identity(issuer, subject, null, false, userId, "Gone"), NO_LINK);

        assertThat(resolved).isEmpty();
    }

    @Test
    void auto_provision_rejects_when_normalized_id_conflicts_case_insensitively() {
        users.create("admin", "Existing", "admin-" + suffix + "@example.com", "password-12", List.of());
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-admin-" + suffix;

        Optional<UserId> resolved = identities.resolve(
                identity(issuer, subject, "admin@corp.example", true, "Admin", "Admin"), AUTO_PROVISION);

        assertThat(resolved).isEmpty();
        assertThat(bindingCount(issuer, subject)).isZero();
    }

    @Test
    void auto_provision_normalizes_email_like_preferred_username() {
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-jd-" + suffix;

        Optional<UserId> resolved = identities.resolve(
                identity(issuer, subject, "jd-" + suffix + "@corp.example", true, "John.Doe@corp.example", "John"),
                AUTO_PROVISION);

        assertThat(resolved).contains(new UserId("john.doe"));
        assertThat(boundUser(issuer, subject)).isEqualTo("john.doe");
    }

    @Test
    void auto_provision_rejects_preferred_username_that_normalizes_to_empty() {
        String issuer = "https://idp.example/" + suffix;
        String subject = "sub-cjk-" + suffix;

        Optional<UserId> resolved =
                identities.resolve(identity(issuer, subject, null, false, "中文用户", "中文"), AUTO_PROVISION);

        assertThat(resolved).isEmpty();
        assertThat(bindingCount(issuer, subject)).isZero();
    }

    @Test
    void concurrent_first_login_creates_a_single_binding() throws Exception {
        String userId = "race-" + suffix;
        String email = "race-" + suffix + "@example.com";
        users.create(userId, "Race", email, "password-12", List.of());
        String issuer = "https://idp.example/race-" + suffix;
        String subject = "sub-race-" + suffix;
        ExternalIdentity identity = identity(issuer, subject, email, true, userId, "Race");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<UserId>> first = pool.submit(() -> {
                start.await();
                return identities.resolve(identity, LINK_EMAIL);
            });
            Future<Optional<UserId>> second = pool.submit(() -> {
                start.await();
                return identities.resolve(identity, LINK_EMAIL);
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(new UserId(userId));
            assertThat(second.get(10, TimeUnit.SECONDS)).contains(new UserId(userId));
        }

        assertThat(bindingCount(issuer, subject)).isOne();
        assertThat(boundUser(issuer, subject)).isEqualTo(userId);
    }

    @Test
    void concurrent_auto_provision_creates_a_single_user_and_binding() throws Exception {
        String preferred = "raceprov." + suffix;
        String expectedId = preferred.toLowerCase(java.util.Locale.ROOT);
        String issuer = "https://idp.example/prov-" + suffix;
        String subject = "sub-prov-" + suffix;
        ExternalIdentity identity =
                identity(issuer, subject, preferred + "@corp.example", true, preferred, "Race Prov");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<UserId>> first = pool.submit(() -> {
                start.await();
                return identities.resolve(identity, AUTO_PROVISION);
            });
            Future<Optional<UserId>> second = pool.submit(() -> {
                start.await();
                return identities.resolve(identity, AUTO_PROVISION);
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(new UserId(expectedId));
            assertThat(second.get(10, TimeUnit.SECONDS)).contains(new UserId(expectedId));
        }

        assertThat(bindingCount(issuer, subject)).isOne();
        assertThat(boundUser(issuer, subject)).isEqualTo(expectedId);
        UserAccount created = users.findById(new UserId(expectedId)).orElseThrow();
        assertThat(created.passwordHash()).isNull();
        assertThat(created.appRoles()).isEmpty();
    }

    private static ExternalIdentity identity(
            String issuer,
            String subject,
            String email,
            boolean emailVerified,
            String preferredUsername,
            String displayName) {
        return new ExternalIdentity(issuer, subject, email, emailVerified, preferredUsername, displayName);
    }

    private void insertBinding(String issuer, String subject, String userId, Instant lastLoginAt) {
        jdbc.sql("""
                        insert into user_identities (issuer, subject, user_id, last_login_at)
                        values (?, ?, ?, ?)
                        """)
                .param(issuer)
                .param(subject)
                .param(userId)
                .param(Timestamp.from(lastLoginAt))
                .update();
    }

    private Instant lastLoginAt(String issuer, String subject) {
        return jdbc.sql("""
                        select last_login_at from user_identities
                        where issuer = ? and subject = ?
                        """)
                .param(issuer)
                .param(subject)
                .query((rs, row) -> rs.getTimestamp("last_login_at").toInstant())
                .single();
    }

    private String boundUser(String issuer, String subject) {
        return jdbc.sql("""
                        select user_id from user_identities
                        where issuer = ? and subject = ?
                        """).param(issuer).param(subject).query(String.class).single();
    }

    private int bindingCount(String issuer, String subject) {
        return jdbc.sql("""
                        select count(*) from user_identities
                        where issuer = ? and subject = ?
                        """).param(issuer).param(subject).query(Integer.class).single();
    }
}
