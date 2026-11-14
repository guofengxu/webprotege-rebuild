package wprebuild.app.web.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.app.shared.PermissionDeniedException;

/** Maps application failures to MCP {@code tools/call} error results without leaking stacks. */
public final class McpToolErrors {

    private static final Logger log = LoggerFactory.getLogger(McpToolErrors.class);

    private static final String GENERIC_MESSAGE = "The tool call failed";

    private McpToolErrors() {}

    static CallToolResult from(Exception failure, JsonMapper json) {
        if (failure instanceof PermissionDeniedException denied) {
            return coded(denied.code(), denied.getMessage(), json);
        }
        if (failure instanceof NotFoundException missing) {
            return coded(missing.code(), missing.getMessage(), json);
        }
        if (failure instanceof ConflictException conflict) {
            return coded(conflict.code(), conflict.getMessage(), json);
        }
        log.error("MCP tool failed", failure);
        return coded("INTERNAL_ERROR", GENERIC_MESSAGE, json);
    }

    private static CallToolResult coded(String code, String message, JsonMapper json) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        try {
            return CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(body))
                    .isError(true)
                    .build();
        } catch (RuntimeException serializationFailed) {
            log.error("Failed to serialize MCP error body", serializationFailed);
            return CallToolResult.builder()
                    .addTextContent("{\"code\":\"INTERNAL_ERROR\",\"message\":\"" + GENERIC_MESSAGE + "\"}")
                    .isError(true)
                    .build();
        }
    }
}
