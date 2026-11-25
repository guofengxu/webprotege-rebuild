package wprebuild.app.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import wprebuild.app.access.UserAccount;

/**
 * Establishes the browser session used by password login and OIDC: principal is the user id string,
 * authorities are the raw app-role names, and the session is registered for in-process revocation.
 */
@Component
public class SessionLogin {

    private final SessionRegistry sessions;

    private final SecurityContextRepository securityContexts = new HttpSessionSecurityContextRepository();

    public SessionLogin(SessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public void establish(UserAccount account, HttpServletRequest request, HttpServletResponse response) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        // A session id from before authentication must not survive the login.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        List<GrantedAuthority> authorities = account.appRoles().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(account.userId().value(), null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        // Session exists only after saveContext; register so UserDeleted can expire it in-process.
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IllegalStateException("session missing after saveContext");
        }
        sessions.registerNewSession(session.getId(), account.userId().value());
    }
}
