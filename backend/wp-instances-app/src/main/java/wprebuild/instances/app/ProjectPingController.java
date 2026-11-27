package wprebuild.instances.app;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth and single-tenant placeholder endpoint (P6-58). Later write/read APIs reuse the same project-id gate.
 */
@RestController
class ProjectPingController {

    private final InstancesProperties properties;

    ProjectPingController(InstancesProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @GetMapping("/api/v1/projects/{projectId}/ping")
    ResponseEntity<Void> ping(@PathVariable UUID projectId) {
        if (!properties.projectId().equals(projectId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().build();
    }
}
