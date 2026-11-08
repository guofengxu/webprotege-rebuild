package wprebuild.app.web;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.app.shared.PermissionDeniedException;
import wprebuild.kernel.revision.ConcurrentAppendException;

/** Maps application failures to RFC 9457 bodies. {@code code} is a top-level extension. */
@RestControllerAdvice
public class ProblemDetailsAdvice {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    public static final String CONCURRENT_APPEND = "CONCURRENT_APPEND";

    /** A handler rejected its own return value. That is a server fault, not a client error. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(PermissionDeniedException.class)
    ResponseEntity<ProblemDetail> denied(PermissionDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(ConcurrentAppendException.class)
    ResponseEntity<ProblemDetail> concurrent(ConcurrentAppendException exception) {
        return problem(HttpStatus.CONFLICT, CONCURRENT_APPEND, exception.getMessage());
    }

    /** {@link org.springframework.web.bind.MethodArgumentNotValidException} is a {@link BindException}. */
    @ExceptionHandler(BindException.class)
    ResponseEntity<ProblemDetail> invalid(BindException exception) {
        return problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, bindingDetail(exception));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> invalidMethod(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            log.error("response validation failed", exception);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "response validation failed");
        }
        return problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, methodDetail(exception));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static String bindingDetail(BindingResult result) {
        String fields = result.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + text(error))
                .collect(Collectors.joining(", "));
        if (!fields.isBlank()) {
            return fields;
        }
        ObjectError global = result.getGlobalError();
        if (global != null) {
            return text(global);
        }
        return "validation failed";
    }

    private static String methodDetail(HandlerMethodValidationException exception) {
        String details = Stream.concat(
                        exception.getParameterValidationResults().stream()
                                .flatMap(result -> result.getResolvableErrors().stream()),
                        exception.getCrossParameterValidationResults().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining(", "));
        if (!details.isBlank()) {
            return details;
        }
        String reason = exception.getReason();
        if (reason == null || reason.isBlank()) {
            return "validation failed";
        }
        return reason;
    }

    private static String text(ObjectError error) {
        String message = error.getDefaultMessage();
        if (message == null || message.isBlank()) {
            return "invalid";
        }
        return message;
    }
}
