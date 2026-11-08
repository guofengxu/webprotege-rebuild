package wprebuild.app.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Transient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import wprebuild.app.access.ApiKeyService;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;

/**
 * Authenticates {@code X-API-Key}. A presented key replaces any session: the header also skips CSRF, so falling
 * back to the cookie would let a cross-site caller ride the victim's session.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeys;

    private final UserService users;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeys, UserService users) {
        this.apiKeys = Objects.requireNonNull(apiKeys, "api keys");
        this.users = Objects.requireNonNull(users, "users");
    }

    static boolean carriesApiKey(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        return header != null && !header.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!carriesApiKey(request)) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        apiKeys.authenticate(request.getHeader(HEADER))
                .flatMap(users::findById)
                .filter(account -> !account.passwordResetRequired())
                .map(ApiKeyAuthenticationFilter::authentication)
                .ifPresent(context::setAuthentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static Authentication authentication(UserAccount account) {
        List<GrantedAuthority> authorities = account.appRoles().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return new ApiKeyAuthentication(account.userId().value(), authorities);
    }

    /**
     * {@code @Transient} keeps {@code SessionManagementFilter} from copying this into a servlet session. A normal
     * token would stay valid after the key is revoked.
     */
    @Transient
    static final class ApiKeyAuthentication extends AbstractAuthenticationToken {

        private final String userId;

        private ApiKeyAuthentication(String userId, Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.userId = Objects.requireNonNull(userId, "user id");
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public String getPrincipal() {
            return userId;
        }
    }
}
