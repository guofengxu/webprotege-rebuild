package wprebuild.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.access.PasswordVerification;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;
import wprebuild.kernel.id.UserId;

/** Session login. These routes stay off the command bus (ADR-007): they only touch the current user. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

    private final UserService users;

    private final CsrfTokenRepository csrfTokens;

    private final SecurityContextRepository securityContexts = new HttpSessionSecurityContextRepository();

    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public AuthController(UserService users, CsrfTokenRepository csrfTokens) {
        this.users = Objects.requireNonNull(users, "users");
        this.csrfTokens = Objects.requireNonNull(csrfTokens, "csrf tokens");
    }

    @Operation(summary = "Sign in and start a session", operationId = "login")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = AuthUserResponse.class)))
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(
            @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (body.username() == null || body.username().isBlank()) {
            return ApiProblems.of(ApiProblems.badRequest("username is required"));
        }
        return switch (users.verifyPassword(body.username(), body.password())) {
            case PasswordVerification.Accepted accepted -> {
                establishSession(accepted.account(), request, response);
                yield ResponseEntity.ok(AuthUserResponse.from(accepted.account()));
            }
            case PasswordVerification.ResetRequired ignored -> ApiProblems.of(ApiProblems.passwordResetRequired());
            case PasswordVerification.Rejected ignored -> ApiProblems.of(ApiProblems.badCredentials());
        };
    }

    @Operation(summary = "End the current session", operationId = "logout")
    @ApiResponse(responseCode = "204", description = "Signed out")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        csrfTokens.saveToken(null, request, response);
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Return the signed-in user", operationId = "getCurrentUser")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = AuthUserResponse.class)))
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        return users.findById(new UserId(authentication.getName()))
                .<ResponseEntity<?>>map(account -> ResponseEntity.ok(AuthUserResponse.from(account)))
                .orElseGet(() -> ApiProblems.of(ApiProblems.authenticationRequired()));
    }

    private void establishSession(UserAccount account, HttpServletRequest request, HttpServletResponse response) {
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
    }

    public record LoginRequest(String username, String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=" + (password == null ? "null" : "***") + "]";
        }
    }
}
