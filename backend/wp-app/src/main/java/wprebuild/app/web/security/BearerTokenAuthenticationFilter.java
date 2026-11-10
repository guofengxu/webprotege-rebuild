package wprebuild.app.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Transient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import wprebuild.app.access.AccessTokenService;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;

/**
 * Authenticates {@code Authorization: Bearer}. A presented token replaces any session or API key: the header also
 * skips CSRF, so falling back to another credential would let a cross-site caller ride it.
 */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer";

    private final AccessTokenService tokens;

    private final UserService users;

    public BearerTokenAuthenticationFilter(AccessTokenService tokens, UserService users) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.users = Objects.requireNonNull(users, "users");
    }

    static boolean carriesBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return false;
        }
        String value = header.stripLeading();
        return value.length() >= SCHEME.length()
                && value.regionMatches(true, 0, SCHEME, 0, SCHEME.length())
                && (value.length() == SCHEME.length() || Character.isWhitespace(value.charAt(SCHEME.length())));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!carriesBearer(request)) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        tokens.authenticate(presentedToken(request))
                .flatMap(users::findById)
                .filter(account -> !account.passwordResetRequired())
                .map(BearerTokenAuthenticationFilter::authentication)
                .ifPresent(context::setAuthentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static String presentedToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return "";
        }
        String value = header.strip();
        if (value.length() <= SCHEME.length()
                || !value.regionMatches(true, 0, SCHEME, 0, SCHEME.length())
                || !Character.isWhitespace(value.charAt(SCHEME.length()))) {
            return "";
        }
        return value.substring(SCHEME.length()).strip();
    }

    private static Authentication authentication(UserAccount account) {
        List<GrantedAuthority> authorities = account.appRoles().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return new BearerAuthentication(account.userId().value(), authorities);
    }

    /**
     * {@code @Transient} keeps {@code SessionManagementFilter} from copying this into a servlet session. A normal
     * token would stay valid after the access token is revoked.
     */
    @Transient
    static final class BearerAuthentication extends AbstractAuthenticationToken {

        private final String userId;

        private BearerAuthentication(String userId, Collection<? extends GrantedAuthority> authorities) {
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
