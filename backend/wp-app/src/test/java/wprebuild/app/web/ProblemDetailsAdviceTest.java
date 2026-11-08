package wprebuild.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.app.shared.PermissionDeniedException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.ConcurrentAppendException;

/** Each mapped exception becomes a ProblemDetail with a stable {@code code}. */
@WebMvcTest(ProblemDetailsAdviceTest.Probe.class)
@AutoConfigureMockMvc(addFilters = false)
// The slice filter does not register a controller nested inside the test class.
@Import({ProblemDetailsAdvice.class, ProblemDetailsAdviceTest.Probe.class})
class ProblemDetailsAdviceTest {

    private static final ProjectId PROJECT = ProjectId.of("11111111-1111-1111-1111-111111111111");

    private final MockMvc mockMvc;

    @Autowired
    ProblemDetailsAdviceTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void not_found_is_404() throws Exception {
        mockMvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("missing project"))
                .andExpect(jsonPath("$.code").value(NotFoundException.CODE));
    }

    @Test
    void permission_denied_is_403() throws Exception {
        mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("permission denied"))
                .andExpect(jsonPath("$.code").value(PermissionDeniedException.CODE));
    }

    @Test
    void conflict_keeps_the_exception_code() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("frame changed"))
                .andExpect(jsonPath("$.code").value("FRAME_CHANGED"));
    }

    @Test
    void concurrent_append_is_409() throws Exception {
        String detail = new ConcurrentAppendException(PROJECT, RevisionNumber.of(1), RevisionNumber.of(2)).getMessage();
        mockMvc.perform(get("/probe/concurrent"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.code").value("CONCURRENT_APPEND"));
    }

    @Test
    void invalid_argument_is_400() throws Exception {
        mockMvc.perform(post("/probe/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail", containsString("name")))
                .andExpect(jsonPath("$.detail", containsString("must not be blank")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void parameter_validation_is_400() throws Exception {
        mockMvc.perform(get("/probe/param-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ProblemDetailsAdvice.VALIDATION_FAILED));
    }

    @Test
    void return_value_validation_is_500() throws Exception {
        mockMvc.perform(get("/probe/return-invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("response validation failed"))
                .andExpect(jsonPath("$.code").value(ProblemDetailsAdvice.INTERNAL_ERROR));
    }

    @RestController
    @RequestMapping("/probe")
    public static class Probe {

        @GetMapping("/missing")
        void missing() {
            throw new NotFoundException("missing project");
        }

        @GetMapping("/denied")
        void denied() {
            throw new PermissionDeniedException("permission denied");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("FRAME_CHANGED", "frame changed");
        }

        @GetMapping("/concurrent")
        void concurrent() {
            throw new ConcurrentAppendException(PROJECT, RevisionNumber.of(1), RevisionNumber.of(2));
        }

        @GetMapping("/param-invalid")
        void paramInvalid() throws Exception {
            throw validationFailure(false);
        }

        @GetMapping("/return-invalid")
        void returnInvalid() throws Exception {
            throw validationFailure(true);
        }

        @PostMapping("/invalid")
        void invalid() throws Exception {
            Method method = Probe.class.getDeclaredMethod("target", String.class);
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "body");
            binding.addError(new FieldError("body", "name", "must not be blank"));
            throw new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);
        }

        void target(String name) {}

        private static HandlerMethodValidationException validationFailure(boolean returnValue) throws Exception {
            Method method = Probe.class.getDeclaredMethod("target", String.class);
            MethodValidationResult result = new MethodValidationResult() {
                @Override
                public Object getTarget() {
                    return new Probe();
                }

                @Override
                public Method getMethod() {
                    return method;
                }

                @Override
                public boolean isForReturnValue() {
                    return returnValue;
                }

                @Override
                public List<ParameterValidationResult> getParameterValidationResults() {
                    return List.of();
                }

                @Override
                public List<MessageSourceResolvable> getCrossParameterValidationResults() {
                    return List.of();
                }
            };
            return new HandlerMethodValidationException(result);
        }
    }
}
