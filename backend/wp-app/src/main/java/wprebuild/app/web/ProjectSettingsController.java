package wprebuild.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.RoleAssignment;
import wprebuild.app.access.UserSummary;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.project.GetProjectMembers;
import wprebuild.app.project.GetProjectSetting;
import wprebuild.app.project.ProjectSettingKeys;
import wprebuild.app.project.PutProjectSetting;
import wprebuild.app.project.RemoveProjectMember;
import wprebuild.app.project.SearchMemberCandidates;
import wprebuild.app.project.SetProjectMember;
import wprebuild.app.web.openapi.SettingsSchemas.DisplayNameSetting;
import wprebuild.app.web.openapi.SettingsSchemas.NewEntitySettings;
import wprebuild.app.web.openapi.SettingsSchemas.PrefixSettings;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Project settings and sharing. Permission is checked by {@link CommandBus}. */
@RestController
@RequestMapping("/api/projects")
@Tag(name = "Project settings")
public class ProjectSettingsController {

    private final CommandBus bus;

    public ProjectSettingsController(CommandBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Operation(summary = "Read prefix settings", operationId = "getPrefixSettings")
    @ApiResponse(
            responseCode = "200",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PrefixSettings.class)))
    @GetMapping("/{id}/settings/prefixes")
    public ResponseEntity<?> prefixes(@PathVariable String id) {
        return read(id, ProjectSettingKeys.PREFIXES);
    }

    @Operation(
            summary = "Replace prefix settings",
            operationId = "putPrefixSettings",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PrefixSettings.class))))
    @ApiResponse(
            responseCode = "200",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PrefixSettings.class)))
    @PutMapping(path = "/{id}/settings/prefixes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putPrefixes(@PathVariable String id, @RequestBody JsonNode body) {
        return write(id, ProjectSettingKeys.PREFIXES, body);
    }

    @Operation(summary = "Read new-entity settings", operationId = "getNewEntitySettings")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NewEntitySettings.class)))
    @GetMapping("/{id}/settings/new-entity")
    public ResponseEntity<?> newEntity(@PathVariable String id) {
        return read(id, ProjectSettingKeys.NEW_ENTITY);
    }

    @Operation(
            summary = "Replace new-entity settings",
            operationId = "putNewEntitySettings",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = NewEntitySettings.class))))
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NewEntitySettings.class)))
    @PutMapping(path = "/{id}/settings/new-entity", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putNewEntity(@PathVariable String id, @RequestBody JsonNode body) {
        return write(id, ProjectSettingKeys.NEW_ENTITY, body);
    }

    @Operation(summary = "Read display-name settings", operationId = "getDisplayNameSettings")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DisplayNameSetting.class))))
    @GetMapping("/{id}/settings/display-names")
    public ResponseEntity<?> displayNames(@PathVariable String id) {
        return read(id, ProjectSettingKeys.DISPLAY_NAMES);
    }

    @Operation(
            summary = "Replace display-name settings",
            operationId = "putDisplayNameSettings",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            array =
                                                    @ArraySchema(
                                                            schema =
                                                                    @Schema(
                                                                            implementation =
                                                                                    DisplayNameSetting.class)))))
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DisplayNameSetting.class))))
    @PutMapping(path = "/{id}/settings/display-names", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putDisplayNames(@PathVariable String id, @RequestBody JsonNode body) {
        return write(id, ProjectSettingKeys.DISPLAY_NAMES, body);
    }

    @Operation(summary = "List project members", operationId = "listProjectMembers")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = MemberResponse.class))))
    @GetMapping("/{id}/members")
    public ResponseEntity<?> members(@PathVariable String id) {
        return withProject(id, (user, projectId) -> {
            List<RoleAssignment> listed = bus.execute(user, new GetProjectMembers(projectId));
            return ResponseEntity.ok(listed.stream().map(MemberResponse::from).toList());
        });
    }

    @Operation(summary = "Search users to add as project members", operationId = "searchMemberCandidates")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserSummaryResponse.class))))
    @GetMapping("/{id}/member-candidates")
    public ResponseEntity<?> memberCandidates(
            @PathVariable String id,
            @RequestParam("q") String query,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return withProject(id, (user, projectId) -> {
            List<UserSummary> found = bus.execute(user, new SearchMemberCandidates(projectId, query, limit));
            return ResponseEntity.ok(
                    found.stream().map(UserSummaryResponse::from).toList());
        });
    }

    @Operation(summary = "Set a project member's role", operationId = "putProjectMember")
    @ApiResponse(
            responseCode = "200",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MemberResponse.class)))
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

    @Operation(summary = "Remove a project member", operationId = "deleteProjectMember")
    @ApiResponse(responseCode = "204", description = "Member removed", content = @Content)
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
