package wprebuild.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.form.AllocateFormId;
import wprebuild.app.form.DeleteProjectForm;
import wprebuild.app.form.GetProjectForm;
import wprebuild.app.form.ListProjectForms;
import wprebuild.app.form.ProjectForm;
import wprebuild.app.form.UpsertProjectForm;
import wprebuild.app.web.openapi.FormSchemas.FreshFormIdResponse;
import wprebuild.app.web.openapi.FormSchemas.ProjectFormResponse;
import wprebuild.app.web.openapi.FormSchemas.PutProjectFormRequest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Project form descriptor management (P6-67 / BS-16). Permission is checked by {@link CommandBus}. */
@RestController
@RequestMapping("/api/projects/{projectId}/forms")
@Tag(name = "Forms")
public class FormDescriptorController {

    private final CommandBus bus;
    private final JsonMapper json;

    public FormDescriptorController(CommandBus bus, JsonMapper json) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Operation(summary = "List project form descriptors", operationId = "listProjectForms")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ProjectFormResponse.class))))
    @GetMapping
    public ResponseEntity<?> list(@PathVariable String projectId) {
        return withProject(
                projectId,
                (user, id) -> ResponseEntity.ok(bus.execute(user, new ListProjectForms(id)).stream()
                        .map(this::toResponse)
                        .toList()));
    }

    @Operation(summary = "Get one project form descriptor", operationId = "getProjectForm")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProjectFormResponse.class)))
    @GetMapping("/{formId}")
    public ResponseEntity<?> get(@PathVariable String projectId, @PathVariable UUID formId) {
        return withProject(
                projectId,
                (user, id) -> ResponseEntity.ok(toResponse(bus.execute(user, new GetProjectForm(id, formId)))));
    }

    @Operation(
            summary = "Create or update a project form descriptor",
            operationId = "putProjectForm",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PutProjectFormRequest.class))))
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProjectFormResponse.class)))
    @PutMapping(path = "/{formId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> put(
            @PathVariable String projectId, @PathVariable UUID formId, @RequestBody JsonNode body) {
        return withProject(projectId, (user, id) -> {
            ParsedPut parsed;
            try {
                parsed = parsePut(formId, body);
            } catch (IllegalArgumentException ex) {
                return invalid(ex.getMessage());
            }
            ProjectForm saved = bus.execute(
                    user, new UpsertProjectForm(id, formId, parsed.descriptorJson(), parsed.criteriaJson()));
            return ResponseEntity.ok(toResponse(saved));
        });
    }

    @Operation(summary = "Delete a project form descriptor", operationId = "deleteProjectForm")
    @ApiResponse(responseCode = "204", description = "Form deleted", content = @Content)
    @DeleteMapping("/{formId}")
    public ResponseEntity<?> delete(@PathVariable String projectId, @PathVariable UUID formId) {
        return withProject(projectId, (user, id) -> {
            bus.execute(user, new DeleteProjectForm(id, formId));
            return ResponseEntity.noContent().build();
        });
    }

    @Operation(summary = "Allocate a fresh form id", operationId = "allocateFormId")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = FreshFormIdResponse.class)))
    @PostMapping("/fresh-id")
    public ResponseEntity<?> freshId(@PathVariable String projectId) {
        return withProject(projectId, (user, id) -> {
            UUID formId = bus.execute(user, new AllocateFormId(id));
            return ResponseEntity.ok(new FreshFormIdResponse(formId));
        });
    }

    private ParsedPut parsePut(UUID formId, JsonNode body) {
        if (body == null || body.isNull() || !body.isObject()) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        JsonNode descriptor = body.get("descriptor");
        if (descriptor == null || descriptor.isNull() || !descriptor.isObject()) {
            throw new IllegalArgumentException("descriptor must be a JSON object");
        }
        JsonNode copied = descriptor.deepCopy();
        if (!(copied instanceof ObjectNode descriptorObject)) {
            throw new IllegalArgumentException("descriptor must be a JSON object");
        }
        JsonNode embeddedId = descriptorObject.get("formId");
        if (embeddedId == null || embeddedId.isNull() || embeddedId.isMissingNode()) {
            descriptorObject.put("formId", formId.toString());
        } else {
            if (!embeddedId.isString()) {
                throw new IllegalArgumentException("descriptor.formId must be a UUID string");
            }
            final UUID parsed;
            try {
                parsed = UUID.fromString(embeddedId.asString());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("descriptor.formId must be a UUID");
            }
            if (!parsed.equals(formId)) {
                throw new IllegalArgumentException("descriptor.formId must match the path form id");
            }
        }
        String criteriaJson = null;
        if (body.has("criteria") && !body.get("criteria").isNull()) {
            JsonNode criteria = body.get("criteria");
            if (!criteria.isObject()) {
                throw new IllegalArgumentException("criteria must be a JSON object");
            }
            criteriaJson = json.writeValueAsString(criteria);
        }
        return new ParsedPut(json.writeValueAsString(descriptorObject), criteriaJson);
    }

    private ProjectFormResponse toResponse(ProjectForm form) {
        JsonNode descriptor = readObject(form.descriptorJson(), "descriptor");
        JsonNode criteria = form.criteriaJson() == null ? null : readObject(form.criteriaJson(), "criteria");
        return new ProjectFormResponse(form.formId(), form.ordinal(), descriptor, criteria);
    }

    private JsonNode readObject(String raw, String label) {
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException(label + " is not a JSON object");
            }
            return node;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(label + " is not valid JSON", ex);
        }
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

    private record ParsedPut(String descriptorJson, String criteriaJson) {}
}
