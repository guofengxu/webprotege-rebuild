package wprebuild.app.web.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.projection.GetProjectionStatus;
import wprebuild.app.projection.ProjectionStatus;
import wprebuild.app.projection.RebuildProjection;
import wprebuild.app.web.ApiProblems;
import wprebuild.app.web.ProblemDetailsAdvice;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** System-admin projection status and rebuild. Permission is checked by {@link CommandBus}. */
@RestController
@RequestMapping("/api/admin/projections")
@Tag(name = "Admin")
public class ProjectionAdminController {

    private final CommandBus bus;

    public ProjectionAdminController(CommandBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Operation(summary = "Get projection catch-up status for a project", operationId = "getProjectionStatus")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProjectionStatusResponse.class)))
    @GetMapping("/{projectId}")
    public ResponseEntity<?> status(@PathVariable String projectId) {
        return withProject(projectId, (user, id) -> {
            ProjectionStatus status = bus.execute(user, new GetProjectionStatus(id));
            return ResponseEntity.ok(ProjectionStatusResponse.from(status));
        });
    }

    @Operation(summary = "Rebuild projection tables for a project", operationId = "rebuildProjection")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProjectionStatusResponse.class)))
    @PostMapping("/{projectId}/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable String projectId) {
        return withProject(projectId, (user, id) -> {
            ProjectionStatus status = bus.execute(user, new RebuildProjection(id));
            return ResponseEntity.ok(ProjectionStatusResponse.from(status));
        });
    }

    private ResponseEntity<?> withProject(String rawId, ProjectAction action) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        final ProjectId projectId;
        try {
            projectId = ProjectId.of(rawId);
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
}
