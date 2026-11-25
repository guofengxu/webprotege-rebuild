package wprebuild.app.web.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;
import wprebuild.app.access.ExternalIdentity;
import wprebuild.app.access.ExternalIdentityService;
import wprebuild.app.access.ExternalLoginPolicy;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;
import wprebuild.kernel.id.UserId;

/**
 * Replaces the OAuth2 authentication with the same session shape as password login. Redirect targets
 * are fixed site paths (no open redirect).
 */
@Component
@ConditionalOnProperty(prefix = "wp.oidc", name = "enabled", havingValue = "true")
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String HOME = "/";

    private static final String UNLINKED = "/login?error=oidc_unlinked";

    private final ExternalIdentityService identities;

    private final UserService users;

    private final OidcProperties oidc;

    private final SessionLogin sessions;

    private final OAuth2AuthorizedClientRepository authorizedClients;

    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public OidcLoginSuccessHandler(
            ExternalIdentityService identities,
            UserService users,
            OidcProperties oidc,
            SessionLogin sessions,
            OAuth2AuthorizedClientRepository authorizedClients) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.users = Objects.requireNonNull(users, "users");
        this.oidc = Objects.requireNonNull(oidc, "oidc");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.authorizedClients = Objects.requireNonNull(authorizedClients, "authorized clients");
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                || !(oauth.getPrincipal() instanceof OidcUser oidcUser)) {
            clearSession(request, response, authentication);
            response.sendRedirect(UNLINKED);
            return;
        }
        String registrationId = oauth.getAuthorizedClientRegistrationId();
        OidcProperties.Provider provider = oidc.provider(registrationId);
        if (provider == null) {
            clearSession(request, response, authentication);
            response.sendRedirect(UNLINKED);
            return;
        }
        ExternalIdentity identity = toIdentity(oidcUser);
        ExternalLoginPolicy policy = new ExternalLoginPolicy(provider.linkByVerifiedEmail(), provider.autoProvision());
        Optional<UserId> resolved = identities.resolve(identity, policy);
        if (resolved.isEmpty()) {
            clearSession(request, response, authentication);
            response.sendRedirect(UNLINKED);
            return;
        }
        Optional<UserAccount> account = users.findById(resolved.get()).filter(user -> !user.deleted());
        if (account.isEmpty()) {
            clearSession(request, response, authentication);
            response.sendRedirect(UNLINKED);
            return;
        }
        // Drop IdP tokens from the session before rewriting authentication to the password-login shape.
        authorizedClients.removeAuthorizedClient(registrationId, oauth, request, response);
        sessions.establish(account.get(), request, response);
        response.sendRedirect(HOME);
    }

    private static ExternalIdentity toIdentity(OidcUser oidcUser) {
        String issuer = oidcUser.getIssuer() != null
                ? oidcUser.getIssuer().toString()
                : Objects.requireNonNull(oidcUser.getClaimAsString("iss"), "iss");
        String subject = Objects.requireNonNull(oidcUser.getSubject(), "sub");
        Boolean verified = oidcUser.getEmailVerified();
        return new ExternalIdentity(
                issuer,
                subject,
                oidcUser.getEmail(),
                Boolean.TRUE.equals(verified),
                oidcUser.getPreferredUsername(),
                oidcUser.getFullName());
    }

    private void clearSession(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        logoutHandler.logout(request, response, authentication);
        SecurityContextHolder.clearContext();
    }
}
