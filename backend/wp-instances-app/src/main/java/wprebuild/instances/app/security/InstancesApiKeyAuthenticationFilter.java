package wprebuild.instances.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.Transient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import wprebuild.instances.app.InstancesApiKeyService;

/**
 * Authenticates {@code X-API-Key} against the instance-store {@code api_keys} table (P6-58).
 *
 * <p>Does not log the header value. A presented but invalid key leaves the caller anonymous → 401 on
 * {@code /api/**}.
 */
public class InstancesApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final InstancesApiKeyService apiKeys;

    public InstancesApiKeyAuthenticationFilter(InstancesApiKeyService apiKeys) {
        this.apiKeys = Objects.requireNonNull(apiKeys, "api keys");
    }

    /** Async dispatches must re-read the header; the key is not stored in a session. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        apiKeys.authenticate(header)
                .map(InstancesApiKeyAuthenticationFilter::authentication)
                .ifPresent(context::setAuthentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static Authentication authentication(UUID keyId) {
        return new InstancesApiKeyAuthentication(keyId);
    }

    /**
     * {@code @Transient} keeps session machinery from copying this into an HTTP session if session
     * policy ever changes; a normal token would stay valid after the key is revoked.
     */
    @Transient
    static final class InstancesApiKeyAuthentication extends AbstractAuthenticationToken {

        private final UUID keyId;

        private InstancesApiKeyAuthentication(UUID keyId) {
            super(List.of(new SimpleGrantedAuthority("ROLE_INSTANCES_API")));
            this.keyId = Objects.requireNonNull(keyId, "key id");
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return keyId;
        }
    }
}
