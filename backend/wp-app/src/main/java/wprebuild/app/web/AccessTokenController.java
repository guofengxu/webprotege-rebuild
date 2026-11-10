package wprebuild.app.web;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.access.AccessTokenService;
import wprebuild.app.access.InvalidRefreshTokenException;
import wprebuild.app.access.IssuedTokens;
import wprebuild.app.access.PasswordResetRequiredException;
import wprebuild.app.access.PasswordVerification;
import wprebuild.app.access.RefreshTokenReusedException;
import wprebuild.app.access.UnknownDeviceException;
import wprebuild.app.access.UserService;
import wprebuild.kernel.id.UserId;

/** Mobile bearer tokens. These routes stay off the command bus (ADR-007): they only touch the current user. */
@RestController
public class AccessTokenController {

    private final AccessTokenService tokens;

    private final UserService users;

    public AccessTokenController(AccessTokenService tokens, UserService users) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.users = Objects.requireNonNull(users, "users");
    }

    @PostMapping(path = "/api/auth/tokens", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> issue(@RequestBody IssueTokenRequest body) {
        if (body == null || body.username() == null || body.username().isBlank()) {
            return ApiProblems.of(ApiProblems.badRequest("username is required"));
        }
        return switch (users.verifyPassword(body.username(), body.password())) {
            case PasswordVerification.Accepted accepted ->
                issueFor(accepted.account().userId(), body.deviceName());
            case PasswordVerification.ResetRequired ignored -> ApiProblems.of(ApiProblems.passwordResetRequired());
            case PasswordVerification.Rejected ignored -> ApiProblems.of(ApiProblems.badCredentials());
        };
    }

    @PostMapping(path = "/api/auth/tokens/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> refresh(@RequestBody RefreshTokenRequest body) {
        try {
            IssuedTokens issued = tokens.refresh(body == null ? null : body.refreshToken());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(IssuedTokensResponse.from(issued));
        } catch (RefreshTokenReusedException ex) {
            return ApiProblems.of(ApiProblems.refreshTokenReused());
        } catch (PasswordResetRequiredException ex) {
            return ApiProblems.of(ApiProblems.passwordResetRequired());
        } catch (InvalidRefreshTokenException ex) {
            return ApiProblems.of(ApiProblems.invalidRefreshToken());
        }
    }

    @GetMapping("/api/me/devices")
    public ResponseEntity<?> list() {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        return ResponseEntity.ok(tokens.list(user));
    }

    @DeleteMapping("/api/me/devices/{tokenId}")
    public ResponseEntity<?> revoke(@PathVariable UUID tokenId) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        try {
            tokens.revoke(user, tokenId);
        } catch (UnknownDeviceException ex) {
            return ApiProblems.of(ApiProblems.notFound("device not found"));
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> issueFor(UserId userId, String deviceName) {
        try {
            IssuedTokens issued = tokens.issue(userId, deviceName);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(IssuedTokensResponse.from(issued));
        } catch (IllegalArgumentException ex) {
            return ApiProblems.of(ApiProblems.badRequest(ex.getMessage()));
        }
    }

    private static UserId currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return new UserId(authentication.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public record IssueTokenRequest(String username, String password, String deviceName) {

        @Override
        public String toString() {
            return "IssueTokenRequest[username=" + username + ", password=" + (password == null ? "null" : "***")
                    + ", deviceName=" + deviceName + "]";
        }
    }

    public record RefreshTokenRequest(String refreshToken) {

        @Override
        public String toString() {
            return "RefreshTokenRequest[refreshToken=***]";
        }
    }
}
