package wprebuild.instances.app;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/projects/{projectId}/batch} (P6-59). */
@RestController
class InstancesBatchWriteController {

    private final InstancesBatchWriteService writes;

    InstancesBatchWriteController(InstancesBatchWriteService writes) {
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    @PostMapping("/api/v1/projects/{projectId}/batch")
    ResponseEntity<BatchWriteResponse> batch(
            @PathVariable UUID projectId, @RequestBody BatchWriteRequest request, Authentication authentication) {
        UUID keyId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(writes.apply(projectId, keyId, request));
    }
}
