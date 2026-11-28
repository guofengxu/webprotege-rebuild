package wprebuild.app.web.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Documentation-only shapes for form descriptor management JSON (P6-67). */
public final class FormSchemas {

    private FormSchemas() {}

    @Schema(
            name = "ProjectFormResponse",
            requiredProperties = {"formId", "ordinal", "descriptor"})
    public record ProjectFormResponse(
            UUID formId,
            int ordinal,

            @Schema(
                    description = "Opaque FormDescriptor Jackson tree (polymorphic control type field)",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            JsonNode descriptor,

            @Schema(description = "Optional selector criteria; null when the form has no selector", nullable = true)
            JsonNode criteria) {}

    @Schema(
            name = "PutProjectFormRequest",
            requiredProperties = {"descriptor"})
    public record PutProjectFormRequest(
            @Schema(
                    description = "FormDescriptor Jackson tree; formId must match the path when present",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            JsonNode descriptor,

            @Schema(description = "Selector criteria object, or null/omitted to clear the selector", nullable = true)
            JsonNode criteria) {}

    @Schema(
            name = "FreshFormIdResponse",
            requiredProperties = {"formId"})
    public record FreshFormIdResponse(UUID formId) {}
}
