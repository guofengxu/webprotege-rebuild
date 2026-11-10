package wprebuild.app.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document for {@code /v3/api-docs}. Errors share the RFC 9457 ProblemDetail schema. */
@Configuration
public class OpenApiConfiguration {

    static final String PROBLEM_MEDIA = "application/problem+json";

    static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Bean
    OpenAPI webprotegeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("WebProtege Rebuild API")
                        .version("0.1.0")
                        .description(
                                "REST API for the WebProtege rebuild (ADR-007). Errors use RFC 9457 ProblemDetail."))
                .components(new Components().addSchemas(PROBLEM_SCHEMA, problemDetailSchema()));
    }

    @Bean
    OpenApiCustomizer problemDetailResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemDetailSchema());
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths()
                    .values()
                    .forEach(item -> item.readOperations().forEach(operation -> {
                        ApiResponses responses = operation.getResponses();
                        if (responses == null) {
                            responses = new ApiResponses();
                            operation.setResponses(responses);
                        }
                        putProblem(responses, "400", "Bad request");
                        putProblem(responses, "401", "Authentication required");
                        putProblem(responses, "403", "Access denied");
                        putProblem(responses, "404", "Not found");
                        putProblem(responses, "409", "Conflict");
                    }));
        };
    }

    private static void putProblem(ApiResponses responses, String code, String description) {
        responses.addApiResponse(code, problemResponse(description));
    }

    private static ApiResponse problemResponse(String description) {
        Schema<?> ref = new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA);
        MediaType media = new MediaType().schema(ref);
        Content content = new Content().addMediaType(PROBLEM_MEDIA, media);
        return new ApiResponse().description(description).content(content);
    }

    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("code", new StringSchema().description("Stable application error code"))
                .additionalProperties(true);
    }
}
