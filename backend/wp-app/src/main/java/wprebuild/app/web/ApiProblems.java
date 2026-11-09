package wprebuild.app.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/** RFC 9457 bodies for authentication failures. {@code code} is a top-level extension. */
public final class ApiProblems {

    public static final String PASSWORD_RESET_REQUIRED = "PASSWORD_RESET_REQUIRED";

    public static final String UPLOAD_TOO_LARGE = "UPLOAD_TOO_LARGE";

    private ApiProblems() {}

    public static ProblemDetail authenticationRequired() {
        return unauthorized("Authentication is required");
    }

    public static ProblemDetail badCredentials() {
        return unauthorized("The username or password is incorrect");
    }

    public static ProblemDetail passwordResetRequired() {
        ProblemDetail problem = unauthorized("Password reset is required before this account can sign in");
        problem.setProperty("code", PASSWORD_RESET_REQUIRED);
        return problem;
    }

    public static ProblemDetail accessDenied() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied");
    }

    public static ProblemDetail badRequest(String detail) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    public static ProblemDetail payloadTooLarge() {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "upload exceeds the size limit");
        problem.setProperty("code", UPLOAD_TOO_LARGE);
        return problem;
    }

    public static ProblemDetail notFound(String detail) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
    }

    public static ResponseEntity<ProblemDetail> of(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    public static void write(HttpServletResponse response, ProblemDetail problem, JsonMapper json) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), problem);
    }

    private static ProblemDetail unauthorized(String detail) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
    }
}
