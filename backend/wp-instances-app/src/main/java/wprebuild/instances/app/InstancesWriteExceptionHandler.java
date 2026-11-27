package wprebuild.instances.app;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import wprebuild.instances.rules.InstanceValidationException;
import wprebuild.instances.rules.Violation;

/** Maps write-path failures to HTTP status codes (P6-59). */
@RestControllerAdvice
class InstancesWriteExceptionHandler {

    @ExceptionHandler(ProjectForbiddenException.class)
    ResponseEntity<Void> forbidden(ProjectForbiddenException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<Map<String, String>> tooLarge(PayloadTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", ex.getMessage() == null ? "payload too large" : ex.getMessage()));
    }

    @ExceptionHandler(InstanceValidationException.class)
    ResponseEntity<Map<String, List<Violation>>> validation(InstanceValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("violations", ex.violations()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "bad request" : ex.getMessage()));
    }

    /** CachedRuleSource contract mismatch / missing projection → 503. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unavailable(IllegalStateException ex) {
        String message = ex.getMessage() == null ? "service unavailable" : ex.getMessage();
        // Match only the known rule-source failure messages (not every JDBC error mentioning "projection").
        if (message.contains("contract_version") || message.contains("has no projection")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", message));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal error"));
    }
}
