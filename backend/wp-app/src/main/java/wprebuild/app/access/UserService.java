package wprebuild.app.access;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.kernel.id.UserId;

/**
 * Creates accounts and checks passwords. The delegating encoder's default id is bcrypt.
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Service
public class UserService {

    /** BCrypt ignores bytes past this point, so a longer password would be stored as a shorter one. */
    static final int BCRYPT_MAX_BYTES = 72;

    private final JdbcUserRepository users;

    private final PasswordEncoder passwordEncoder;

    private final String dummyHash;

    public UserService(JdbcUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = Objects.requireNonNull(users, "users");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        // Missing accounts pay the same BCrypt cost, so timing does not reveal whether the username exists.
        this.dummyHash = passwordEncoder.encode("timing-equalization");
    }

    @Transactional
    public UserAccount create(
            String userId, String displayName, String email, String rawPassword, List<String> appRoles) {
        UserId id = new UserId(userId);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        UserAccount account = new UserAccount(id, displayName, email, encode(rawPassword), appRoles);
        try {
            users.insert(account);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("user already exists: " + id.value(), ex);
        }
        return account;
    }

    public Optional<UserAccount> findById(UserId userId) {
        return users.findById(Objects.requireNonNull(userId, "user id"));
    }

    @Transactional
    public void setPassword(UserId userId, String rawPassword) {
        Objects.requireNonNull(userId, "user id");
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        String hash = encode(rawPassword);
        if (users.updatePasswordHash(userId, hash) == 0) {
            throw new IllegalArgumentException("unknown user: " + userId.value());
        }
    }

    public PasswordVerification verifyPassword(String username, String rawPassword) {
        if (username == null || username.isBlank()) {
            return new PasswordVerification.Rejected();
        }
        UserAccount account;
        try {
            account = users.findById(new UserId(username)).orElse(null);
        } catch (IllegalArgumentException ex) {
            return new PasswordVerification.Rejected();
        }
        if (account == null) {
            passwordEncoder.matches(timingPassword(rawPassword), dummyHash);
            return new PasswordVerification.Rejected();
        }
        if (account.passwordResetRequired()) {
            return new PasswordVerification.ResetRequired();
        }
        if (!acceptable(rawPassword)) {
            // Same BCrypt cost as a missing account, so a blank or over-long password is not an oracle.
            passwordEncoder.matches(timingPassword(rawPassword), dummyHash);
            return new PasswordVerification.Rejected();
        }
        if (!passwordEncoder.matches(rawPassword, account.passwordHash())) {
            return new PasswordVerification.Rejected();
        }
        return new PasswordVerification.Accepted(account);
    }

    private String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return null;
        }
        if (exceedsBcrypt(rawPassword)) {
            throw new IllegalArgumentException("password must be at most 72 UTF-8 bytes");
        }
        return passwordEncoder.encode(rawPassword);
    }

    private String timingPassword(String rawPassword) {
        return acceptable(rawPassword) ? rawPassword : "timing-placeholder";
    }

    private static boolean acceptable(String rawPassword) {
        return rawPassword != null && !rawPassword.isBlank() && !exceedsBcrypt(rawPassword);
    }

    private static boolean exceedsBcrypt(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}
