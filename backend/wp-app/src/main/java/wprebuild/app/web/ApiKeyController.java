package wprebuild.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wprebuild.app.access.ApiKeyInfo;
import wprebuild.app.access.ApiKeyService;
import wprebuild.app.access.IssuedKey;
import wprebuild.app.access.UnknownApiKeyException;
import wprebuild.kernel.id.UserId;

/** The signed-in user's own API keys. These routes stay off the command bus (ADR-007). */
@RestController
@RequestMapping("/api/me/api-keys")
@Tag(name = "API keys")
public class ApiKeyController {

    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = Objects.requireNonNull(apiKeys, "api keys");
    }

    @Operation(summary = "Create an API key", operationId = "createApiKey")
    @ApiResponse(
            responseCode = "201",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = IssuedApiKeyResponse.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody CreateApiKeyRequest body) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        try {
            IssuedKey issued = apiKeys.issue(user, body == null ? null : body.name());
            return ResponseEntity.created(URI.create("/api/me/api-keys/" + issued.keyId()))
                    .cacheControl(CacheControl.noStore())
                    .body(IssuedApiKeyResponse.from(issued));
        } catch (IllegalArgumentException ex) {
            return ApiProblems.of(ApiProblems.badRequest(ex.getMessage()));
        }
    }

    @Operation(summary = "List API keys", operationId = "listApiKeys")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ApiKeyInfo.class))))
    @GetMapping
    public ResponseEntity<?> list() {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        List<ApiKeyInfo> keys = apiKeys.list(user);
        return ResponseEntity.ok(keys);
    }

    @Operation(summary = "Revoke an API key", operationId = "revokeApiKey")
    @ApiResponse(responseCode = "204", description = "API key revoked", content = @Content)
    @DeleteMapping("/{keyId}")
    public ResponseEntity<?> revoke(@PathVariable UUID keyId) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        try {
            apiKeys.revoke(user, keyId);
        } catch (UnknownApiKeyException ex) {
            return ApiProblems.of(ApiProblems.notFound("API key not found"));
        }
        return ResponseEntity.noContent().build();
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

    public record CreateApiKeyRequest(String name) {}
}
