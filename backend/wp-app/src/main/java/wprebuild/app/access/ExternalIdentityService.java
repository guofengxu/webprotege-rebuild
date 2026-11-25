package wprebuild.app.access;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.kernel.id.UserId;

/**
 * Resolves an external {@code (issuer, subject)} to a local account. Does not auto-provision or
 * link by email unless the caller enables those flags on {@link ExternalLoginPolicy}.
 *
 * <p>Not {@code final}: {@code @Transactional} is implemented by a subclass proxy.
 */
@Service
public class ExternalIdentityService {

    private static final int MIN_USER_ID_LENGTH = 3;

    private static final int MAX_USER_ID_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(ExternalIdentityService.class);

    private final JdbcUserIdentityRepository identities;

    private final JdbcUserRepository users;

    public ExternalIdentityService(JdbcUserIdentityRepository identities, JdbcUserRepository users) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.users = Objects.requireNonNull(users, "users");
    }

    @Transactional
    public Optional<UserId> resolve(ExternalIdentity identity, ExternalLoginPolicy policy) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(policy, "policy");
        Optional<UserId> bound = identities.findUserId(identity.issuer(), identity.subject());
        if (bound.isPresent()) {
            return touchIfActive(identity, bound.get());
        }
        if (policy.linkByVerifiedEmail()) {
            Optional<UserId> linked = linkByVerifiedEmail(identity);
            if (linked.isPresent()) {
                return linked;
            }
        }
        if (policy.autoProvision()) {
            return autoProvision(identity);
        }
        return Optional.empty();
    }

    private Optional<UserId> linkByVerifiedEmail(ExternalIdentity identity) {
        if (!identity.emailVerified() || identity.email() == null) {
            return Optional.empty();
        }
        List<UserAccount> matches = users.findActiveByEmailIgnoreCase(identity.email());
        if (matches.size() != 1) {
            return Optional.empty();
        }
        UserAccount match = matches.getFirst();
        if (match.appRoles().contains(AppRole.SYSTEM_ADMIN.name())) {
            return Optional.empty();
        }
        return bind(identity, match.userId());
    }

    private Optional<UserId> autoProvision(ExternalIdentity identity) {
        Optional<String> normalized = normalizeUserId(identity.preferredUsername());
        if (normalized.isEmpty()) {
            log.warn(
                    "external identity {}:{} rejected: preferred username did not normalize to a valid user id",
                    identity.issuer(),
                    identity.subject());
            return Optional.empty();
        }
        String userIdValue = normalized.get();
        UserId userId = new UserId(userIdValue);
        Optional<UserAccount> existing = users.findById(userId);
        if (existing.isPresent()) {
            // Concurrent first login may have just committed the winner's user+binding.
            return recoverConcurrentProvision(identity, userIdValue);
        }
        String displayName = identity.displayName() != null ? identity.displayName() : userIdValue;
        // Insert directly so a concurrent duplicate does not mark this transaction rollback-only.
        UserAccount created = new UserAccount(userId, displayName, identity.email(), null, List.of(), null);
        if (users.insert(created) == 0) {
            return recoverConcurrentProvision(identity, userIdValue);
        }
        return bind(identity, userId);
    }

    private Optional<UserId> recoverConcurrentProvision(ExternalIdentity identity, String userIdValue) {
        Optional<UserId> raced = identities.findUserId(identity.issuer(), identity.subject());
        if (raced.isPresent()) {
            return touchIfActive(identity, raced.get());
        }
        // Pre-existing account (or deleted) with this id: do not steal it.
        log.warn(
                "external identity {}:{} rejected: user id {} already exists",
                identity.issuer(),
                identity.subject(),
                userIdValue);
        return Optional.empty();
    }

    private Optional<UserId> bind(ExternalIdentity identity, UserId userId) {
        Optional<UserId> stored = identities.insert(identity.issuer(), identity.subject(), userId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return active(stored.get());
    }

    private Optional<UserId> touchIfActive(ExternalIdentity identity, UserId userId) {
        Optional<UserId> active = active(userId);
        if (active.isPresent()) {
            identities.touchLastLogin(identity.issuer(), identity.subject());
        }
        return active;
    }

    private Optional<UserId> active(UserId userId) {
        Optional<UserAccount> account = users.findById(userId);
        if (account.isEmpty() || account.get().deleted()) {
            return Optional.empty();
        }
        return Optional.of(userId);
    }

    /**
     * Lowercases with {@link Locale#ROOT}; if the value looks like an email, keeps the local part;
     * keeps only {@code [a-z0-9._-]}; length must be 3–64.
     */
    static Optional<String> normalizeUserId(String preferredUsername) {
        if (preferredUsername == null || preferredUsername.isBlank()) {
            return Optional.empty();
        }
        String value = preferredUsername.strip().toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(0, at);
        }
        StringBuilder kept = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') {
                kept.append(ch);
            }
        }
        String normalized = kept.toString();
        if (normalized.length() < MIN_USER_ID_LENGTH || normalized.length() > MAX_USER_ID_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }
}
