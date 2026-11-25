package wprebuild.app.web.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Clears any half-open OAuth2 session and redirects to the login page. Provider error details never
 * appear in the URL.
 */
@Component
@ConditionalOnProperty(prefix = "wp.oidc", name = "enabled", havingValue = "true")
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private static final String FAILED = "/login?error=oidc_failed";

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect(FAILED);
    }
}
