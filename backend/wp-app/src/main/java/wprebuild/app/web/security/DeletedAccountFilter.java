package wprebuild.app.web.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;
import wprebuild.app.web.ApiProblems;
import wprebuild.kernel.id.UserId;

/**
 * Rejects browser sessions whose account was soft-deleted. API keys and bearer tokens are checked when those
 * credentials are presented (P6-29); this filter only covers session authentication.
 *
 * <p>The deleted-status cache and {@code SessionRegistry} are process-local. Another instance discovers a deleted
 * account at latest when its cache entry expires ({@code wp.security.deleted-check-ttl}). Cross-instance immediate
 * revocation is P6-40.
 *
 * <p>Ordered after {@link SecurityContextHolderFilter} so the session principal is already loaded. Not a
 * {@code @Component}: Spring Boot would also register it on the servlet chain; {@code SecurityConfig} adds it only
 * to the API security filter chain.
 */
public class DeletedAccountFilter extends OncePerRequestFilter {

    private static final int CACHE_MAX_SIZE = 10_000;

    private static final Logger log = LoggerFactory.getLogger(DeletedAccountFilter.class);

    private final UserService users;

    private final JsonMapper json;

    private final Cache<String, Boolean> deleted;

    private final boolean cacheEnabled;

    public DeletedAccountFilter(UserService users, JsonMapper json, SecurityProperties properties) {
        this.users = Objects.requireNonNull(users, "users");
        this.json = Objects.requireNonNull(json, "json");
        Objects.requireNonNull(properties, "properties");
        Duration ttl = properties.deletedCheckTtl();
        this.cacheEnabled = !ttl.isZero();
        Caffeine<Object, Object> builder = Caffeine.newBuilder().recordStats().maximumSize(CACHE_MAX_SIZE);
        if (cacheEnabled) {
            builder.expireAfterWrite(ttl);
        }
        this.deleted = builder.build();
    }

    /** Records that the account is deleted so a concurrent cache load cannot re-insert {@code false}. */
    public void markDeleted(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        deleted.put(userId.value(), Boolean.TRUE);
    }

    /** Snapshot for tests that assert the cache avoids repeat lookups within the TTL. */
    public CacheStats cacheStats() {
        return deleted.stats();
    }

    /** Test helper: drop a cached entry so the next request reloads from the database. */
    public void invalidate(UserId userId) {
        Objects.requireNonNull(userId, "user id");
        deleted.invalidate(userId.value());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ApiKeyAuthenticationFilter.carriesApiKey(request)
                || BearerTokenAuthenticationFilter.carriesBearer(request)) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        // Only browser logins store the context in the session; @WithMockUser and token auth do not.
        if (session == null
                || session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof UsernamePasswordAuthenticationToken) || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        String userId = authentication.getName();
        if (!isDeleted(userId)) {
            chain.doFilter(request, response);
            return;
        }
        log.info("rejecting session for deleted user {}", userId);
        SecurityContextHolder.clearContext();
        session.invalidate();
        ApiProblems.write(response, ApiProblems.authenticationRequired(), json);
    }

    private boolean isDeleted(String userId) {
        if (!cacheEnabled) {
            return lookupDeleted(userId);
        }
        return Boolean.TRUE.equals(deleted.get(userId, this::lookupDeleted));
    }

    private Boolean lookupDeleted(String userId) {
        Optional<UserAccount> account;
        try {
            account = users.findById(new UserId(userId));
        } catch (IllegalArgumentException ex) {
            return Boolean.TRUE;
        }
        return account.map(UserAccount::deleted).orElse(Boolean.TRUE);
    }
}
