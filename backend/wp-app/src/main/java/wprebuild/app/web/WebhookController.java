package wprebuild.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.integration.CreateWebhook;
import wprebuild.app.integration.DeleteWebhook;
import wprebuild.app.integration.DeliverySummary;
import wprebuild.app.integration.ListWebhookDeliveries;
import wprebuild.app.integration.ListWebhooks;
import wprebuild.app.integration.SecretChange;
import wprebuild.app.integration.UpdateWebhook;
import wprebuild.app.integration.Webhook;
import wprebuild.app.integration.WebhookDelivery;
import wprebuild.app.integration.WebhookEventType;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Project webhook management. Permission is checked by {@link CommandBus}. */
@RestController
@RequestMapping("/api/projects/{projectId}/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private final CommandBus bus;

    public WebhookController(CommandBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Operation(summary = "List project webhooks", operationId = "listWebhooks")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = WebhookResponse.class))))
    @GetMapping
    public ResponseEntity<?> list(@PathVariable String projectId) {
        return withProject(
                projectId,
                (user, id) -> ResponseEntity.ok(bus.execute(user, new ListWebhooks(id)).stream()
                        .map(WebhookResponse::from)
                        .toList()));
    }

    @Operation(summary = "Create a project webhook", operationId = "createWebhook")
    @ApiResponse(
            responseCode = "201",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = WebhookResponse.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@PathVariable String projectId, @RequestBody CreateWebhookRequest body) {
        return withProject(projectId, (user, id) -> {
            if (body == null) {
                return invalid("request body is required");
            }
            Set<WebhookEventType> eventTypes;
            try {
                eventTypes = parseEventTypes(body.eventTypes());
            } catch (IllegalArgumentException ex) {
                return invalid(ex.getMessage());
            }
            String secret = blankToNull(body.secret());
            if (secret != null && secret.length() < 16) {
                return invalid("secret must be at least 16 characters");
            }
            try {
                Webhook created = bus.execute(
                        user,
                        new CreateWebhook(
                                id,
                                requireUrl(body.payloadUrl()),
                                eventTypes,
                                secret,
                                body.enabled() == null || body.enabled()));
                return ResponseEntity.created(URI.create("/api/projects/" + id + "/webhooks/" + created.webhookId()))
                        .body(WebhookResponse.from(created));
            } catch (IllegalArgumentException ex) {
                return invalid(ex.getMessage());
            }
        });
    }

    @Operation(summary = "Update a project webhook", operationId = "updateWebhook")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = WebhookResponse.class)))
    @PutMapping(path = "/{webhookId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(
            @PathVariable String projectId, @PathVariable UUID webhookId, @RequestBody UpdateWebhookRequest body) {
        return withProject(projectId, (user, id) -> {
            if (body == null) {
                return invalid("request body is required");
            }
            Set<WebhookEventType> eventTypes;
            SecretChange secretChange;
            try {
                eventTypes = parseEventTypes(body.eventTypes());
                secretChange = parseSecretChange(body.secretChange(), body.secret());
            } catch (IllegalArgumentException ex) {
                return invalid(ex.getMessage());
            }
            if (body.enabled() == null) {
                return invalid("enabled is required");
            }
            try {
                Webhook updated = bus.execute(
                        user,
                        new UpdateWebhook(
                                id,
                                webhookId,
                                requireUrl(body.payloadUrl()),
                                eventTypes,
                                secretChange,
                                body.enabled()));
                return ResponseEntity.ok(WebhookResponse.from(updated));
            } catch (IllegalArgumentException ex) {
                return invalid(ex.getMessage());
            }
        });
    }

    @Operation(summary = "Delete a project webhook", operationId = "deleteWebhook")
    @ApiResponse(responseCode = "204", description = "Webhook deleted", content = @Content)
    @DeleteMapping("/{webhookId}")
    public ResponseEntity<?> delete(@PathVariable String projectId, @PathVariable UUID webhookId) {
        return withProject(projectId, (user, id) -> {
            bus.execute(user, new DeleteWebhook(id, webhookId));
            return ResponseEntity.noContent().build();
        });
    }

    @Operation(summary = "List recent webhook deliveries", operationId = "listWebhookDeliveries")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = WebhookDeliveryResponse.class))))
    @GetMapping("/{webhookId}/deliveries")
    public ResponseEntity<?> deliveries(
            @PathVariable String projectId,
            @PathVariable UUID webhookId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return withProject(projectId, (user, id) -> {
            try {
                List<WebhookDelivery> listed = bus.execute(user, new ListWebhookDeliveries(id, webhookId, limit));
                return ResponseEntity.ok(
                        listed.stream().map(WebhookDeliveryResponse::from).toList());
            } catch (IllegalArgumentException ex) {
                return invalid(ex.getMessage());
            }
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

    private static Set<WebhookEventType> parseEventTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("event types must not be empty");
        }
        EnumSet<WebhookEventType> types = EnumSet.noneOf(WebhookEventType.class);
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("event type must not be blank");
            }
            try {
                types.add(WebhookEventType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown event type: " + value);
            }
        }
        return Set.copyOf(new LinkedHashSet<>(types));
    }

    private static SecretChange parseSecretChange(String action, String secret) {
        String normalized =
                action == null || action.isBlank() ? "KEEP" : action.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "KEEP" -> SecretChange.keep();
            case "CLEAR" -> SecretChange.clear();
            case "REPLACE" -> {
                String value = blankToNull(secret);
                if (value == null) {
                    throw new IllegalArgumentException("secret is required when secretChange is REPLACE");
                }
                if (value.length() < 16) {
                    throw new IllegalArgumentException("secret must be at least 16 characters");
                }
                yield SecretChange.replace(value);
            }
            default -> throw new IllegalArgumentException("secretChange must be KEEP, REPLACE, or CLEAR");
        };
    }

    private static String requireUrl(String payloadUrl) {
        if (payloadUrl == null || payloadUrl.isBlank()) {
            throw new IllegalArgumentException("payloadUrl is required");
        }
        return payloadUrl.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
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

    public record CreateWebhookRequest(String payloadUrl, List<String> eventTypes, String secret, Boolean enabled) {}

    public record UpdateWebhookRequest(
            String payloadUrl,
            List<String> eventTypes,
            String secretChange,
            String secret,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Boolean enabled) {}

    public record WebhookResponse(
            UUID webhookId,
            String payloadUrl,
            List<String> eventTypes,
            boolean hasSecret,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            DeliverySummaryResponse lastDelivery) {

        static WebhookResponse from(Webhook webhook) {
            return new WebhookResponse(
                    webhook.webhookId(),
                    webhook.payloadUrl().toString(),
                    webhook.eventTypes().stream().map(Enum::name).toList(),
                    webhook.hasSecret(),
                    webhook.enabled(),
                    webhook.createdAt(),
                    webhook.updatedAt(),
                    DeliverySummaryResponse.from(webhook.lastDelivery()));
        }
    }

    public record DeliverySummaryResponse(String status, Integer statusCode, Instant finishedAt, String error) {

        static DeliverySummaryResponse from(DeliverySummary summary) {
            if (summary == null) {
                return null;
            }
            return new DeliverySummaryResponse(
                    summary.status(), summary.statusCode(), summary.finishedAt(), summary.error());
        }
    }

    public record WebhookDeliveryResponse(
            long deliveryId,
            String status,
            int attempts,
            Integer lastStatusCode,
            String lastError,
            Instant createdAt,
            Instant finishedAt) {

        static WebhookDeliveryResponse from(WebhookDelivery delivery) {
            return new WebhookDeliveryResponse(
                    delivery.deliveryId(),
                    delivery.status(),
                    delivery.attempts(),
                    delivery.lastStatusCode(),
                    delivery.lastError(),
                    delivery.createdAt(),
                    delivery.finishedAt());
        }
    }
}
