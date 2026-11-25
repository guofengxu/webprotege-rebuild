package wprebuild.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.access.PasswordVerification;
import wprebuild.app.access.UserService;
import wprebuild.app.web.security.OidcProperties;
import wprebuild.app.web.security.SessionLogin;
import wprebuild.kernel.id.UserId;

/** Session login. These routes stay off the command bus (ADR-007): they only touch the current user. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

    private final UserService users;

    private final CsrfTokenRepository csrfTokens;

    private final SessionLogin sessions;

    private final OidcProperties oidc;

    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public AuthController(
            UserService users, CsrfTokenRepository csrfTokens, SessionLogin sessions, OidcProperties oidc) {
        this.users = Objects.requireNonNull(users, "users");
        this.csrfTokens = Objects.requireNonNull(csrfTokens, "csrf tokens");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.oidc = Objects.requireNonNull(oidc, "oidc");
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
                sessions.establish(accepted.account(), request, response);
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
                .filter(account -> !account.deleted())
                .<ResponseEntity<?>>map(account -> ResponseEntity.ok(AuthUserResponse.from(account)))
                .orElseGet(() -> ApiProblems.of(ApiProblems.authenticationRequired()));
    }

    @Operation(summary = "List available login providers", operationId = "listLoginProviders")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = LoginProviderResponse.class))))
    @GetMapping("/providers")
    public List<LoginProviderResponse> listLoginProviders() {
        if (!oidc.enabled()) {
            return List.of();
        }
        return oidc.providers().entrySet().stream()
                .map(entry -> new LoginProviderResponse(
                        entry.getKey(), entry.getValue().displayName(), "/oauth2/authorization/" + entry.getKey()))
                .toList();
    }

    public record LoginRequest(String username, String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=" + (password == null ? "null" : "***") + "]";
        }
    }
}
