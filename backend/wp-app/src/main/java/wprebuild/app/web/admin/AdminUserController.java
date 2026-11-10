package wprebuild.app.web.admin;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.access.AppRole;
import wprebuild.app.access.UserPage;
import wprebuild.app.access.UserProfile;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.web.ApiProblems;
import wprebuild.app.web.ProblemDetailsAdvice;
import wprebuild.kernel.id.UserId;

/** Administrator account operations. Permission is checked by {@link CommandBus}. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final CommandBus bus;

    public AdminUserController(CommandBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        UserId caller = currentUser();
        if (caller == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        try {
            UserPage listed = bus.execute(caller, new ListUsers(page, size));
            return ResponseEntity.ok(AdminUserPage.from(listed));
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody CreateUserRequest body) {
        UserId caller = currentUser();
        if (caller == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        if (body == null) {
            return invalid("request body is required");
        }
        try {
            UserProfile created = bus.execute(
                    caller, new CreateUser(body.userId(), body.displayName(), body.email(), body.initialPassword()));
            return ResponseEntity.created(
                            URI.create("/api/admin/users/" + created.userId().value()))
                    .body(AdminUserResponse.from(created));
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    @PutMapping(path = "/{userId}/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resetPassword(@PathVariable String userId, @RequestBody ResetPasswordRequest body) {
        UserId caller = currentUser();
        if (caller == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        if (body == null) {
            return invalid("request body is required");
        }
        try {
            bus.execute(caller, new ResetUserPassword(new UserId(userId), body.newPassword()));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    @PutMapping(path = "/{userId}/app-roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setRoles(@PathVariable String userId, @RequestBody SetAppRolesRequest body) {
        UserId caller = currentUser();
        if (caller == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        if (body == null) {
            return invalid("request body is required");
        }
        try {
            UserProfile updated = bus.execute(caller, new SetUserAppRoles(new UserId(userId), roles(body.appRoles())));
            return ResponseEntity.ok(AdminUserResponse.from(updated));
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    private static Set<AppRole> roles(List<String> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("app roles must not be null");
        }
        Set<AppRole> roles = EnumSet.noneOf(AppRole.class);
        for (String name : raw) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("app role must not be blank");
            }
            try {
                roles.add(AppRole.valueOf(name.strip()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown app role: " + name.strip());
            }
        }
        return Set.copyOf(roles);
    }

    private static ResponseEntity<ProblemDetail> invalid(String detail) {
        ProblemDetail problem = ApiProblems.badRequest(detail);
        problem.setProperty("code", ProblemDetailsAdvice.VALIDATION_FAILED);
        return ApiProblems.of(problem);
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

    public record CreateUserRequest(String userId, String displayName, String email, String initialPassword) {

        @Override
        public String toString() {
            return "CreateUserRequest[userId=" + userId + ", displayName=" + displayName + ", email=" + email
                    + ", initialPassword=***]";
        }
    }

    public record ResetPasswordRequest(String newPassword) {

        @Override
        public String toString() {
            return "ResetPasswordRequest[newPassword=" + (newPassword == null ? "null" : "***") + "]";
        }
    }

    public record SetAppRolesRequest(List<String> appRoles) {}

    public record AdminUserResponse(
            String userId, String displayName, String email, List<String> appRoles, boolean passwordResetRequired) {

        public AdminUserResponse {
            Objects.requireNonNull(userId, "user id");
            Objects.requireNonNull(displayName, "display name");
            appRoles = List.copyOf(Objects.requireNonNull(appRoles, "app roles"));
        }

        static AdminUserResponse from(UserProfile profile) {
            return new AdminUserResponse(
                    profile.userId().value(),
                    profile.displayName(),
                    profile.email(),
                    profile.appRoles(),
                    profile.passwordResetRequired());
        }
    }

    public record AdminUserPage(List<AdminUserResponse> users, int page, int size, long total) {

        public AdminUserPage {
            users = List.copyOf(Objects.requireNonNull(users, "users"));
        }

        static AdminUserPage from(UserPage page) {
            return new AdminUserPage(
                    page.users().stream().map(AdminUserResponse::from).toList(),
                    page.page(),
                    page.size(),
                    page.total());
        }
    }
}
