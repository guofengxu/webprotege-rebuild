package wprebuild.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.ontology.EntityFormsResult;
import wprebuild.app.ontology.GetEntityForms;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Entity form read API (P6-69 / BS-17). Permission is checked by {@link CommandBus}.
 *
 * <p>GateGuard facts: called by {@code GetEntityFormsApiTest}; Glob found none; no data files; user
 * asked {@code 完成下一个任务卡}.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/entity-forms")
@Tag(name = "Forms")
public class EntityFormsController {

    private final CommandBus bus;

    public EntityFormsController(CommandBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Operation(summary = "Get applicable forms for an entity", operationId = "getEntityForms")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EntityFormsResult.class)))
    @GetMapping
    public ResponseEntity<?> get(
            @PathVariable String projectId,
            @RequestParam("entity") String entity,
            @RequestParam(name = "formId", required = false) List<UUID> formIds) {
        return withProject(projectId, (user, id) -> {
            IRI iri = entityIri(entity);
            Set<UUID> filter =
                    formIds == null || formIds.isEmpty() ? Set.of() : Set.copyOf(new LinkedHashSet<>(formIds));
            return ResponseEntity.ok(bus.execute(user, new GetEntityForms(id, iri, filter)));
        });
    }

    private ResponseEntity<?> withProject(String projectId, ProjectAction action) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        final ProjectId id;
        try {
            id = ProjectId.of(projectId);
        } catch (IllegalArgumentException ex) {
            return invalid("project id is not a UUID");
        }
        try {
            return action.run(user, id);
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    private static IRI entityIri(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("entity is required");
        }
        IRI iri = IRI.create(raw.strip());
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("entity must be an absolute IRI");
        }
        return iri;
    }

    private static ResponseEntity<?> invalid(String detail) {
        var problem = ApiProblems.badRequest(detail);
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
