package wprebuild.app.web;

import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.RoleAssignment;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.project.GetProjectMembers;
import wprebuild.app.project.GetProjectSetting;
import wprebuild.app.project.ProjectSettingKeys;
import wprebuild.app.project.PutProjectSetting;
import wprebuild.app.project.RemoveProjectMember;
import wprebuild.app.project.SetProjectMember;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Project settings and sharing. Permission is checked by {@link CommandBus}. */
@RestController
@RequestMapping("/api/projects")
public class ProjectSettingsController {

    private final CommandBus bus;

    public ProjectSettingsController(CommandBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @GetMapping("/{id}/settings/prefixes")
    public ResponseEntity<?> prefixes(@PathVariable String id) {
        return read(id, ProjectSettingKeys.PREFIXES);
    }

    @PutMapping(path = "/{id}/settings/prefixes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putPrefixes(@PathVariable String id, @RequestBody JsonNode body) {
        return write(id, ProjectSettingKeys.PREFIXES, body);
    }

    @GetMapping("/{id}/settings/new-entity")
    public ResponseEntity<?> newEntity(@PathVariable String id) {
        return read(id, ProjectSettingKeys.NEW_ENTITY);
    }

    @PutMapping(path = "/{id}/settings/new-entity", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putNewEntity(@PathVariable String id, @RequestBody JsonNode body) {
        return write(id, ProjectSettingKeys.NEW_ENTITY, body);
    }

    @GetMapping("/{id}/settings/display-names")
    public ResponseEntity<?> displayNames(@PathVariable String id) {
        return read(id, ProjectSettingKeys.DISPLAY_NAMES);
    }

    @PutMapping(path = "/{id}/settings/display-names", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putDisplayNames(@PathVariable String id, @RequestBody JsonNode body) {
        return write(id, ProjectSettingKeys.DISPLAY_NAMES, body);
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<?> members(@PathVariable String id) {
        return withProject(id, (user, projectId) -> {
            List<RoleAssignment> listed = bus.execute(user, new GetProjectMembers(projectId));
            return ResponseEntity.ok(listed.stream().map(MemberResponse::from).toList());
        });
    }

    @PutMapping(path = "/{id}/members/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putMember(
            @PathVariable String id, @PathVariable String userId, @RequestBody RoleRequest body) {
        if (body == null || body.role() == null || body.role().isBlank()) {
            return invalid("role is required");
        }
        final ProjectRole role;
        try {
            role = ProjectRole.valueOf(body.role());
        } catch (IllegalArgumentException ex) {
            return invalid("role is not a project role");
        }
        return withProject(id, (user, projectId) -> {
            RoleAssignment stored = bus.execute(user, new SetProjectMember(projectId, new UserId(userId), role));
            return ResponseEntity.ok(MemberResponse.from(stored));
        });
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<?> deleteMember(@PathVariable String id, @PathVariable String userId) {
        return withProject(id, (user, projectId) -> {
            bus.execute(user, new RemoveProjectMember(projectId, new UserId(userId)));
            return ResponseEntity.noContent().build();
        });
    }

    private ResponseEntity<?> read(String id, String key) {
        return withProject(
                id, (user, projectId) -> ResponseEntity.ok(bus.execute(user, new GetProjectSetting(projectId, key))));
    }

    private ResponseEntity<?> write(String id, String key, JsonNode body) {
        if (body == null || body.isNull()) {
            return invalid("request body is required");
        }
        return withProject(
                id,
                (user, projectId) -> ResponseEntity.ok(bus.execute(user, new PutProjectSetting(projectId, key, body))));
    }

    private ResponseEntity<?> withProject(String id, ProjectAction action) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        final ProjectId projectId;
        try {
            projectId = ProjectId.of(id);
        } catch (IllegalArgumentException ex) {
            return invalid("project id is not a UUID");
        }
        try {
            return action.run(user, projectId);
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
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

    @FunctionalInterface
    private interface ProjectAction {
        ResponseEntity<?> run(UserId user, ProjectId projectId);
    }

    public record RoleRequest(String role) {}

    public record MemberResponse(String userId, String role) {
        static MemberResponse from(RoleAssignment assignment) {
            return new MemberResponse(
                    assignment.userId().value(), assignment.role().name());
        }
    }
}
